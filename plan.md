选定**影院/演出抢票选座系统**，眼光很好——它比秒杀多了"座位锁定 + 超时释放 + 实时同步"三层复杂度，但也正因如此，性能设计能讲得非常透。

下面给你一套**可直接照着实现**的完整方案。我会把重心放在**架构决策 + 核心代码骨架 + 目录结构**上，你按图施工即可。

------

# 一、整体架构总览

```
┌─────────────┐     ┌─────────────┐
│  Vue3 前端   │◄───►│   Nginx     │  静态资源 + 反向代理 + 限流
│  (座位图/WS) │     └──────┬──────┘
└─────────────┘            │
                    ┌─────▼──────┐
                    │ Spring Boot │  (可多实例)
                    │  单体应用    │
                    └──┬───┬───┬──┘
          ┌───────────┘   │   └───────────┐
     ┌────▼────┐   ┌──────▼──────┐  ┌─────▼─────┐
     │  Redis  │   │   MySQL 8   │  │ RabbitMQ  │
     │ Bitmap  │   │  订单/基础   │  │ 延迟消息   │
     │ 座位状态 │   │   数据      │  │ 超时关单   │
     └────┬────┘   └─────────────┘  └─────┬─────┘
          │                                │
     ┌────▼────────────────────────────────▼─────┐
     │  Redis Pub/Sub  →  WebSocket 实时广播      │
     └───────────────────────────────────────────┘
```

**核心分工（这是本项目的灵魂，务必理解）：**

| 组件             | 承担职责              | 一句话理由                 |
| ---------------- | --------------------- | -------------------------- |
| **Redis Bitmap** | 座位实时状态（锁/售） | 位操作原子、内存极小、O(1) |
| **MySQL**        | 订单、用户、基础数据  | 权威持久化，最终一致       |
| **RabbitMQ**     | 15分钟延迟关单        | 削峰 + 可靠超时            |
| **WebSocket**    | 座位变化实时推送      | 避免疯狂轮询刷新           |

------

# 二、数据模型设计

## 2.1 Redis Key 设计（最关键）

座位状态用 **两个 Bitmap**，这是整个系统的性能核心：

```
# 场次座位状态（核心，bit 位 = 场内座位序号 0~N-1）
cinema:session:{sid}:lock    Bitmap  已锁定（含已售）
cinema:session:{sid}:sold    Bitmap  已售出（已支付）

# 场次信息缓存
cinema:session:info:{sid}    Hash    影片/影厅/时间/价格
cinema:hall:layout:{hid}     String  影厅座位布局 JSON（行/列/坐标/类型）

# 用户待支付订单（幂等 + 快速查"我锁的座位"）
cinema:user:pending:{uid}    String  当前待支付订单号
```

**为什么用 Bitmap 而不是 Set/String？**

- 一个 300 座影厅，Bitmap 只占 **约 40 字节**，Set 要几 KB
- `SETBIT`/`GETBIT` 是 **O(1) 原子操作**，天然防超卖
- `GETRANGE` 一次取回整个座位图，前端渲染零压力
- 单 Key 已足够快（Redis 单节点位操作可达 10w+ QPS），**课程设计不需要分段**，分段反而增加 Lua 复杂度——这是"不过度设计"的加分点（可在报告里写明这个权衡）

**座位状态推导规则（前端渲染依据）：**

| lock位 | sold位 | 含义             |
| ------ | ------ | ---------------- |
| 0      | 0      | ✅ 可选           |
| 1      | 0      | 🔒 已锁（待支付） |
| 1      | 1      | ⬛ 已售           |

> "已锁的座位是不是我锁的" → 从**我的待支付订单**里读（一个用户一场只允许一个待支付订单），不需要额外的 owner 结构，设计更简洁。

## 2.2 MySQL 表设计

```
cinema       影院：id, name, address
hall         影厅：id, cinema_id, name, seat_count
seat         座位：id, hall_id, seat_index(0~N-1，对应bitmap位), 
                  row_no, col_no, seat_type(普通/VIP), x, y
movie        影片：id, title, poster, duration, description, status
session      场次：id, movie_id, hall_id, start_time, end_time, price, status
user         用户：id, username, password, phone
`order`      订单：id, order_no(UK), user_id, session_id, 
                  status, total_amount, seat_count, expire_at, paid_at
order_item   订单座位：id, order_id, session_id, seat_index, price
```

**订单状态**：`PENDING_PAY`(待支付) → `PAID`(已支付) / `CANCELLED`(超时取消)

**关键约束**：

- `uk_order_no`：订单号唯一（雪花算法）
- `uk_user_session_pending`：**`(user_id, session_id)` + status=PENDING_PAY 唯一索引**（用部分索引或业务层保证），防止同一用户重复占座——这是幂等防重的核心
- `order_item` 在**锁座时**就写入，支付只改 `order.status` + sold bitmap

------

# 三、后端实现

## 3.1 目录结构（按领域分包，比按层分包更专业）

```
cinema-ticket/
├── sql/
│   ├── schema.sql                  # 建表
│   └── init-data.sql               # 影院/影厅/座位/影片初始数据
├── docker/
│   ├── docker-compose.yml          # MySQL + Redis + RabbitMQ + Nginx
│   ├── nginx/conf.d/
│   └── prometheus/
├── docs/
│   ├── api.md                      # 接口文档
│   ├── 架构设计.md
│   └── 压测报告.md
└── cinema-server/                  # Spring Boot 单体（推荐单体，好部署好演示）
    ├── pom.xml
    └── src/main/
        ├── java/com/yourname/cinema/
        │   ├── CinemaApplication.java
        │   ├── common/                       # 公共
        │   │   ├── result/R.java             # 统一响应
        │   │   ├── exception/                # 全局异常 + 业务异常
        │   │   └── context/UserContext.java  # ThreadLocal 登录态
        │   ├── config/                       # 配置
        │   │   ├── RedisConfig.java          # 序列化 + Lua 脚本 Bean
        │   │   ├── RedissonConfig.java
        │   │   ├── WebSocketConfig.java
        │   │   ├── MybatisPlusConfig.java
        │   │   ├── SecurityConfig.java       # JWT
        │   │   └── ThreadPoolConfig.java     # 自定义线程池（不要用默认！）
        │   ├── interceptor/
        │   │   ├── JwtInterceptor.java
        │   │   └── RateLimitInterceptor.java # 接口限流
        │   ├── modules/                      # ★ 业务模块（按领域）
        │   │   ├── movie/       {controller, service, mapper, entity, vo}
        │   │   ├── session/     {controller, service, mapper, entity, vo}
        │   │   ├── seat/                     # ★ 座位图
        │   │   │   ├── controller/SeatController.java
        │   │   │   ├── service/SeatService.java      # 座位图查询
        │   │   │   └── vo/SeatMapVO.java
        │   │   ├── order/                    # ★★ 核心
        │   │   │   ├── controller/OrderController.java
        │   │   │   ├── service/OrderService.java     # 锁座/支付/取消
        │   │   │   ├── mapper/OrderMapper.java
        │   │   │   └── entity/{Order, OrderItem}.java
        │   │   ├── payment/                  # 模拟支付
        │   │   │   └── service/MockPaymentService.java
        │   │   └── user/        {controller, service, mapper, entity}
        │   ├── infra/                        # ★ 基础设施
        │   │   ├── redis/
        │   │   │   ├── RedisKeys.java       # Key 常量
        │   │   │   └── SeatLuaService.java   # ★ Lua 脚本调用封装
        │   │   ├── mq/
        │   │   │   ├── OrderDelayProducer.java    # 发延迟消息
        │   │   │   └── OrderTimeoutConsumer.java  # 超时关单消费者
        │   │   ├── ws/
        │   │   │   ├── SeatWsHandler.java         # WebSocket 连接管理
        │   │   │   └── SeatEventPublisher.java    # 发布座位变更事件
        │   │   └── lock/DistributedLock.java      # Redisson 封装
        │   ├── job/
        │   │   └── OrderTimeoutCompensateJob.java  # ★ 补偿任务（防MQ丢消息）
        │   └── support/
        └── resources/
            ├── lua/                          # ★ Lua 脚本
            │   ├── lock_seat.lua
            │   ├── confirm_seat.lua
            │   └── release_seat.lua
            ├── mapper/                       # MyBatis XML（复杂查询）
            ├── application.yml
            └── application-dev.yml
```

## 3.2 核心 Lua 脚本（`resources/lua/`）

**① 锁座 `lock_seat.lua`** —— 原子性防超卖的核心

```
-- KEYS[1] = lock bitmap, KEYS[2] = sold bitmap
-- ARGV[1..] = 座位索引列表
local lockKey, soldKey = KEYS[1], KEYS[2]
local conflict = {}

-- 第一遍：检查冲突（任一已售或已锁则失败）
for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    if redis.call('GETBIT', soldKey, seat) == 1 
       or redis.call('GETBIT', lockKey, seat) == 1 then
        table.insert(conflict, seat)
    end
end

if #conflict > 0 then
    return cjson.encode({ok = false, conflict = conflict})
end

-- 第二遍：全部可用，执行锁定
for i = 1, #ARGV do
    redis.call('SETBIT', lockKey, tonumber(ARGV[i]), 1)
end
return cjson.encode({ok = true, conflict = {}})
```

**② 支付确认 `confirm_seat.lua`** —— 锁 → 售

```
-- KEYS[1]=lock, KEYS[2]=sold ; ARGV=座位索引
for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    if redis.call('GETBIT', KEYS[1], seat) == 1 then
        redis.call('SETBIT', KEYS[2], seat, 1)   -- 标记已售
    end
end
return 1
```

**③ 释放 `release_seat.lua`** —— 超时/取消，只清未售的

```
-- KEYS[1]=lock, KEYS[2]=sold ; ARGV=座位索引
local released = {}
for i = 1, #ARGV do
    local seat = tonumber(ARGV[i])
    if redis.call('GETBIT', KEYS[2], seat) == 0 then  -- 未售才释放
        redis.call('SETBIT', KEYS[1], seat, 0)
        table.insert(released, seat)
    end
end
return cjson.encode(released)
```

> **Lua 加载方式**：用 `DefaultRedisScript` + `@Bean` 预加载，避免每次传输脚本体。若上 Redis Cluster，Key 需加 hash tag `cinema:session:{sid}:lock` 保证多 Key 同 slot。

## 3.3 核心业务流程

### 🔒 锁座下单（最核心）

```
@Transactional
public LockResult lockSeats(Long userId, Long sessionId, List<Integer> seatIndexes) {
    // 1. 业务校验：一次最多 4 个座位、场次未开演
    checkSeatLimit(seatIndexes);

    // 2. 幂等：若用户在本场已有待支付订单 → 先释放旧座位（或直接复用返回）
    releaseUserPendingIfExists(userId, sessionId);

    // 3. ★ Redis Lua 原子锁座
    String lockKey = RedisKeys.sessionLock(sessionId);
    String soldKey = RedisKeys.sessionSold(sessionId);
    LuaResult res = seatLuaService.lockSeats(lockKey, soldKey, seatIndexes);
    if (!res.isOk()) {
        throw new BizException("座位已被占用", res.getConflict()); // 返回冲突座位给前端刷新
    }

    try {
        // 4. 落库：创建订单 + order_item（状态 PENDING_PAY，expire_at = now+15min）
        Order order = createPendingOrder(userId, sessionId, seatIndexes);
        
        // 5. 发 15 分钟延迟消息 → RabbitMQ
        orderDelayProducer.sendDelay(order.getOrderNo(), 15 * 60 * 1000);
        
        // 6. ★ 实时广播：其他用户看到座位变灰
        seatEventPublisher.publish(sessionId, 
            SeatEvent.locked(seatIndexes));
        
        return new LockResult(order.getOrderNo(), order.getExpireAt());
    } catch (Exception e) {
        // ★ 补偿：DB 失败则释放 Redis 座位，避免"幽灵锁座"
        seatLuaService.releaseSeats(lockKey, soldKey, seatIndexes);
        throw e;
    }
}
```

### 💰 支付（模拟）

```
@Transactional
public void pay(String orderNo, Long userId) {
    Order order = orderMapper.selectByOrderNo(orderNo);
    // 1. 校验：属于本人 + 状态 PENDING_PAY + 未过期
    validatePayable(order, userId);

    // 2. 模拟支付成功（课程设计无需接真实网关，做一个"模拟支付中→成功"即可）
    
    // 3. ★ Lua：lock → sold
    seatLuaService.confirmSeats(lockKey, soldKey, seatIndexes);
    
    // 4. 更新订单状态 PAID
    orderMapper.updateStatus(orderNo, OrderStatus.PAID);
    
    // 5. 广播售出
    seatEventPublisher.publish(sessionId, SeatEvent.sold(seatIndexes));
}
```

### ⏰ 超时释放（双重保险）

**主链路：RabbitMQ 延迟消息**

```
@RabbitListener(queues = "order.timeout.queue")
public void onOrderTimeout(String orderNo) {
    closeIfUnpaid(orderNo);
}

private void closeIfUnpaid(String orderNo) {
    Order order = orderMapper.selectByOrderNo(orderNo);
    if (order.getStatus() != PENDING_PAY) return;   // 已支付/已取消 → 忽略
    
    seatLuaService.releaseSeats(lockKey, soldKey, order.getSeatIndexes());
    orderMapper.updateStatus(orderNo, CANCELLED);
    seatEventPublisher.publish(sessionId, SeatEvent.released(seatIndexes));
}
```

**兜底补偿：定时任务**（必须做！防 MQ 消息丢失导致座位永久锁死）

```
@Scheduled(fixedDelay = 60_000)   // 每分钟
public void compensateTimeoutOrders() {
    // 查 status=PENDING_PAY 且 expire_at < now 的订单
    List<Order> expired = orderMapper.selectExpiredPendingOrders();
    expired.forEach(o -> closeIfUnpaid(o.getOrderNo()));
}
```

> 这个"**MQ 延迟消息 + 定时补偿**"双保险，是答辩时非常出彩的工程严谨性体现。

### 📡 WebSocket 实时同步

```
// 连接管理：sessionId -> Set<WebSocketSession>（本机）
// 跨实例：通过 Redis Pub/Sub 广播到所有节点
@Component
public class SeatWsHandler extends TextWebSocketHandler {
    private final Map<Long, Set<WebSocketSession>> roomMap = new ConcurrentHashMap<>();
    
    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
        Long sessionId = extractSessionId(s);   // ws://.../ws/seat/{sessionId}
        roomMap.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(s);
    }
    
    // 收到 Redis Pub/Sub 事件 → 推送给本场次所有连接
    @RedisListener(channel = "seat:event")
    public void onSeatEvent(SeatEvent event) {
        roomMap.getOrDefault(event.getSessionId(), Set.of())
               .forEach(s -> s.sendMessage(toJson(event)));
    }
}
```

推送事件格式：

```
{ "type": "LOCKED|RELEASED|SOLD", "sessionId": 1001, "seats": [12,13] }
```

------

# 四、前端实现

## 4.1 目录结构

```
cinema-web/
├── src/
│   ├── main.ts
│   ├── api/
│   │   ├── request.ts          # axios 封装（拦截器、JWT、统一错误处理）
│   │   ├── session.ts          # 场次/影片
│   │   ├── seat.ts             # 座位图
│   │   ├── order.ts            # 锁座/支付/订单
│   │   └── user.ts
│   ├── components/
│   │   ├── SeatMap/            # ★★ 核心组件
│   │   │   ├── index.vue       # 座位图容器（渲染 + 选中）
│   │   │   ├── SeatItem.vue    # 单个座位（状态样式）
│   │   │   ├── SeatLegend.vue  # 图例（可选/已售/已选）
│   │   │   └── useSeatMap.ts   # 状态计算逻辑
│   │   ├── Countdown.vue       # 15分钟支付倒计时
│   │   └── Empty.vue / Loading.vue
│   ├── views/
│   │   ├── Home.vue            # 热映影片
│   │   ├── MovieDetail.vue     # 影片详情 + 场次列表
│   │   ├── SeatSelect.vue      # ★ 选座页（座位图 + 确认选座）
│   │   ├── OrderConfirm.vue    # 订单确认
│   │   ├── Payment.vue         # 模拟支付 + 倒计时
│   │   ├── OrderList.vue       # 我的订单
│   │   └── admin/              # 管理端
│   │       ├── SessionManage.vue   # 场次管理
│   │       ├── HallManage.vue      # 影厅/座位布局管理
│   │       └── MovieManage.vue
│   ├── stores/                 # Pinia
│   │   ├── user.ts
│   │   └── seat.ts             # 选中座位、场次上下文
│   ├── router/index.ts
│   ├── types/index.ts          # TS 类型定义
│   ├── utils/
│   │   ├── ws.ts               # ★ WebSocket 封装（重连/心跳）
│   │   └── bitmap.ts           # 位图解析（后端返回 → 0/1 数组）
│   └── styles/
├── package.json
└── vite.config.ts
```

## 4.2 座位图组件要点

**状态计算**（`useSeatMap.ts`）：

```
type SeatStatus = 'AVAILABLE' | 'SOLD' | 'LOCKED_OTHER' | 'LOCKED_MINE' | 'SELECTED'

function calcStatus(i: number, sold: Uint8Array, locked: Uint8Array, 
                    myLocked: Set<number>, selected: Set<number>): SeatStatus {
  if (sold[i])            return 'SOLD'          // 已售（灰，不可点）
  if (locked[i]) 
    return myLocked.has(i) ? 'LOCKED_MINE'       // 我锁的（高亮）
                           : 'LOCKED_OTHER'      // 别人锁的（灰）
  if (selected.has(i))    return 'SELECTED'      // 我本地选中（未提交）
  return 'AVAILABLE'
}
```

**渲染 & 交互**：

- 用 **CSS Grid** 按 `row_no/col_no` 布局（或用 `x/y` 绝对定位，更灵活）
- 每个座位一个小 `div`，状态 → 不同 class（颜色）
- 点击 `AVAILABLE` → 加入 `selected`（本地暂存，最多 4 个）
- 点击 "确认选座" → 调 `lockSeats()` → 跳支付页
- **锁座返回冲突** → 弹出"座位已被抢走"+自动刷新座位图（用户体验亮点）

**WebSocket 实时更新**：

```
// utils/ws.ts
const ws = new WebSocket(`${WS_BASE}/ws/seat/${sessionId}`)
ws.onmessage = (e) => {
  const evt = JSON.parse(e.data)
  // 局部更新座位状态，无需刷新整页
  seatStore.applySeatEvent(evt)   // LOCKED→置灰 / RELEASED→恢复可选 / SOLD→置灰
}
ws.onclose = () => setTimeout(connect, 3000)   // 断线重连
```

> 300 个座位用 `v-for` 渲染完全没压力。若影厅超大（>1000 座），再考虑 Canvas 渲染，可在文档里提一句作为扩展。

------

# 五、部署、监控与压测

## 5.1 部署（`docker-compose.yml`）

```
services:
  mysql:      image: mysql:8
  redis:      image: redis:7        # 座位状态 Bitmap
  rabbitmq:   image: rabbitmq:3-management   # 延迟消息插件
  app:        build: ./cinema-server         # 可 --scale 2 起多实例
  nginx:      image: nginx          # 反代 + 负载均衡 + 静态资源
  prometheus: image: prom/prometheus
  grafana:    image: grafana/grafana
```

## 5.2 监控（Actuator + Prometheus + Grafana）

必看指标：**接口 QPS / P99 延迟、Redis 命中率、JVM 内存与 GC、订单各状态数量**。

## 5.3 压测方案（决定分数的部分）

用 **JMeter** 跑这三个场景，出**优化前 vs 优化后**对比图：

| 场景           | 压测内容                     | 关键验证指标                 |
| -------------- | ---------------------------- | ---------------------------- |
| 座位图查询     | 1000 并发读                  | QPS 提升（Redis vs 直查 DB） |
| **锁座抢购** ⭐ | 1000 并发抢同场次 100 个座位 | **0 超卖**、错误率、P99      |
| 混合场景       | 读座位图 + 锁座 + 支付       | 综合 QPS、DB 负载            |

> **"1000 并发抢 100 座，最终恰好卖出 100 个、0 超卖"** —— 这句话配上 JMeter 截图，就是最有力的正确性证明，务必做出来。

------

# 六、开发排期（5 周）

| 周次   | 任务                                                     | 产出         |
| ------ | -------------------------------------------------------- | ------------ |
| **W1** | 环境搭建、建表、基础 CRUD（影院/影片/影厅/场次/座位）    | 数据能跑通   |
| **W2** | ★ 座位图 + Redis Bitmap + 锁座 Lua + 下单                | 核心链路通   |
| **W3** | ★ 模拟支付 + MQ 延迟关单 + 补偿任务 + WebSocket 实时推送 | 完整业务闭环 |
| **W4** | 前端完善（选座/支付/订单/管理端）+ Prometheus/Grafana    | 可演示系统   |
| **W5** | 压测、调优、写设计文档与压测报告、做 PPT                 | 交付         |

------

# 七、最后的叮嘱

1. **别一上来就分布式**。先跑通**单实例 + Redis Bitmap**，能用、能压测、能演示，再按需扩展多实例。
2. **锁座 Lua 是命门**，把它单独拎出来反复测试（单线程/并发都要测），确保 0 超卖。
3. **补偿任务一定要写**。MQ 丢消息导致座位永久锁死，是这类系统最常见的事故，你主动防住了就是亮点。
4. **报告里讲清"为什么"**：为什么用 Bitmap 而不是 Set？为什么单 Key 不分片？为什么需要延迟消息 + 定时补偿？——**讲清权衡比堆功能更得分**。

------


# P0 增量设计 Spec — 影院抢票选座系统

> 日期:2026-08-31  
> 路径:A. 走 P0 全集(用户已选)  
> 场景:**课程设计答辩 / 学校实训**(用户已选)  
> 基线:W1-W5 已完成 + Phase A/B/F 优化已落地(详见 `docs/实现方案.md`)  
> 关联:基于 `docs/superpowers/specs/2026-08-31-improvements-roadmap.md` 的 P0 段

---

## 1. 目标与非目标

### 1.1 目标
在不重做架构(Redis Bitmap + ZSet 延迟队列 + WebSocket 已通过 250 并发压测)的前提下,把 `改进与新功能头脑风暴 plan.md` 中标 P0 的 9 项合并为一次增量交付,统一设计、分阶段实施,达到课程设计答辩级别的"工程严谨性 + 业务完整性 + 演示效果"三优。

### 1.2 非目标(本 spec 明确不做)
- 不接真实支付(N4 真实支付 / N4 沙箱);退票用模拟退场
- 不引入 Spring Security 全量(Q7 RBAC);继续用现有拦截器
- 不接 ES / Meilisearch(F1 用 MySQL LIKE)
- 不上 Redis Cluster(P1 延后);但 key 模板已按 Cluster 友好设计
- 不做 P1/P2 全部项目;只把 P0 9 项做透
- 不重做移动端 / SSR / 小程序(U3/Q3 延后)

### 1.3 范围
9 项 P0,每项独立可验收、可单测、可压测回归:

| ID | 简称 | 类别 | 估时 |
| --- | --- | --- | --- |
| E1 | 拆 `OrderService` | 工程重构 | 1.5d |
| P5 | bitmap 冷启动重建 | 稳定性 | 0.5d |
| E4 | `@RateLimit` 注解 + Redis 限流 | 工程 | 0.5d |
| E5 | `@Idempotent` 注解 + 幂等键 | 工程 | 0.5d |
| N1 | 退票流程 | 业务 | 1.0d |
| N2 | 电子票二维码 | 业务 | 0.5d |
| O3 | 管理端经营看板 | 业务 | 1.0d |
| F1 | 电影搜索 + 筛选 | 业务 | 0.5d |
| D1 | 数据大屏(实时) | 演示 | 1.0d |
| **合计** |  |  | **~7d** |

> 估时基于"对 W1-W5 + Phase A-F 已熟悉"的开发者;首次做约 +30%。

---

## 2. 架构决策

### 2.1 公共基座(3 个新注解 + 1 个 AOP 工具包)
为让 E4/E5 单点改动 ≤ 半天,统一引入 `common/annotation/` 下的 AOP 工具包:

```
common/
├── annotation/
│   ├── RateLimit.java         // 限流(per-method,Redis 滑动窗口)
│   ├── Idempotent.java        // 幂等(per-method,Redis SETNX)
│   └── AdminAction.java       // 管理员操作审计(供后续 E6 复用,本 spec 留 hook)
├── aop/
│   ├── RateLimitAspect.java
│   ├── IdempotentAspect.java
│   └── AdminActionAspect.java  // 空实现,本 spec 不填内容,只留 Aspect 入口
└── ratelimit/
    └── RedisSlidingWindow.java  // Lua 原子实现
```

设计原则:注解不感知业务,只接 SpEL 表达式;失败抛 `BizException`;响应走 `R` 统一封装。

### 2.2 状态机扩展(N1)
现状 0=PENDING_PAY / 1=PAID / 2=CANCELLED。N1 引入 3=REFUNDING / 4=REFUNDED:

```
0 PENDING_PAY ─pay→ 1 PAID ─refund→ 3 REFUNDING ─mockRefund→ 4 REFUNDED
       │
       └─timeout/cancel→ 2 CANCELLED  (终态)
1 PAID 退票成功后:座位走 release_seat.lua(与超时一致), 资金走 mock 退
3 REFUNDING 是中间态,正常情况毫秒级;异常情况(下游故障)由 OrderTimeoutCompensateJob 兜底
```

约束:
- 仅 `PAID` 可发起退票
- 退票前置:校验场次未开场(`start_time > now`)、订单属于本人
- 退票不可逆(REFUNDED 不可再退)
- 沿用 CAS 推进:`UPDATE order SET status=3 WHERE order_no=? AND status=1 AND user_id=?`

### 2.3 数据库变更(集中区)
1 张新表 + 4 处字段新增 + 1 个 DDL 补丁。详见 §6 集中 DDL。

### 2.4 限流维度
- 锁座:`uid + sessionId`, 5 次/秒(防止同一用户连点)
- 支付:`uid + orderNo`, 3 次/分钟(防止错点)
- 退票:`uid + orderNo`, 1 次/分钟(防止重复退)
- 全部接口总入口:`ip + path`, 50 次/秒(兜底)

### 2.5 二维码内容(N2)
- payload:`{orderNo}|{userId}|{sessionId}|{exp}`
- HMAC-SHA256(payload, secret) 签名,secret 存配置(`application.yml`)
- 客户端扫码后调用 `GET /api/tickets/verify?payload=...&sig=...` 校验
- 一次性:同 payload 验证成功后置 `verified_at`,二次扫码返"已使用"

---

## 3. 9 项设计

### 3.1 E1 拆 `OrderService`

**目标**:把 27K 字节单文件拆为 4 个 service + 1 个 core,每个 ≤ 300 行。

**新结构**:
```
modules/order/
├── service/
│   ├── OrderLockService.java       // 锁座下单 + 幂等释放旧单
│   ├── OrderPayService.java        // 支付 + 退票
│   ├── OrderCancelService.java     // 主动取消 + 超时关单
│   ├── OrderQueryService.java      // myOrders / detail
│   └── core/
│       ├── OrderCore.java          // CAS 推进 / 公共校验
│       └── SeatBitmapGuard.java    // P5 bitmap 冷启动守护
├── strategy/                       // (预留)Phase F 策略模式
```

**依赖**:
- `OrderLockService` → `OrderCore` + `SeatLuaService` + `DelayQueue` + `SeatEventPublisher`
- `OrderPayService` → `OrderCore` + `SeatLuaService` + `MockPaymentService` + `MockRefundService` (N1)
- `OrderCancelService` → `OrderCore` + `SeatLuaService` + `DelayQueue`(扫描器)
- `OrderQueryService` → Mappers(只读)

**接口稳定性**:Controller 不变,只换内部注入。已有 `OrderService` 标记 `@Deprecated`,3 个迭代后删除。

**风险点**:`OrderCore` 抽错会导致 4 个 service 都要绕;**先写 `OrderCore` 的 5 个核心方法的单元测试,再迁移**。

**验收**:
- 每个新文件 ≤ 300 行(用 `wc -l` 校验)
- `OrderService.java` 仅留 `@Deprecated` 委托(≤ 50 行)
- 现有 `test_mixed.py` 200 并发压测 P99 不退化 ±10%
- 4 个 service 至少 1 个核心方法有 JUnit 单测

### 3.2 P5 bitmap 冷启动重建

**目标**:服务重启 / Redis flush 后,锁座不会出现"看似可选实则已售"的并发漏洞。

**方案**:
1. `SeatBitmapGuard.ensureBitmaps(sessionId)` — 懒加载,**第一次访问时**检查 bitmap 是否存在
2. 不存在 → 从 `order_item` 表聚合: `SELECT seat_index FROM order_item WHERE session_id=? AND order_id IN (SELECT id FROM \`order\` WHERE session_id=? AND status IN (0,1))`
3. 重建:对 lock bitmap `SETBIT idx 1`(所有 status=0/1 座位);对 sold bitmap `SETBIT idx 1`(仅 status=1)
4. 用 Lua 脚本包成原子操作,避免重建途中被并发锁座
5. 重建结果写日志: `[bitmap-recover] sid={} lock={} sold={} cost={}ms`

**触发点**:
- 启动时(可选,只对"近期"场次,过滤 start_time > now-7d)
- 锁座前 fallback(若 `EXISTS lock_key = 0` 则触发)
- 提供管理端接口 `POST /api/admin/bitmaps/recover?sessionId={}` 手动触发

**验收**:
- `FLUSHDB` 后锁座,后端能自动恢复 bitmap 不超卖
- kill -9 后启动,`POST /api/orders/lock` 正常返回
- 管理端手动恢复接口在 swagger-ui 可见

### 3.3 E4 `@RateLimit` 注解

**注解**:
```java
@Target(METHOD) @Retention(RUNTIME)
public @interface RateLimit {
    String key();                    // SpEL, 如 "#userId + ':' + #dto.sessionId"
    int permits() default 1;
    int window() default 1;
    TimeUnit unit() default TimeUnit.SECONDS;
    String message() default "请求过于频繁";
}
```

**实现**:`RedisSlidingWindow` 用 Lua 脚本:
```lua
-- KEYS[1]=桶key, ARGV[1]=permits, ARGV[2]=window_ms, ARGV[3]=now_ms, ARGV[4]=member
local key = KEYS[1]
local p, w, now, m = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), ARGV[4]
redis.call('ZREMRANGEBYSCORE', key, 0, now - w)
local cnt = redis.call('ZCARD', key)
if cnt >= p then return 0 end
redis.call('ZADD', key, now, m)
redis.call('PEXPIRE', key, w)
return 1
```

**应用点**:
- `OrderController.lock()` → `@RateLimit(key="#userId+':lock:'+#dto.sessionId", permits=5, window=1)`
- `OrderController.pay()` → `@RateLimit(key="#userId+':pay:'+#orderNo", permits=3, window=1, unit=MINUTES)`
- `OrderController.refund()` (N1 新增) → `@RateLimit(key="#userId+':refund:'+#orderNo", permits=1, window=1, unit=MINUTES)`

**验收**:
- 100 并发同用户同场次锁座,第 6 个返回 429 / `code=42900`
- 3 次/分钟支付,第 4 次返回限流错误
- 滑动窗口不是固定窗口(跨窗请求应被允许)

### 3.4 E5 `@Idempotent` 注解

**注解**:
```java
@Target(METHOD) @Retention(RUNTIME)
public @interface Idempotent {
    String key();                    // SpEL
    long ttl() default 2;            // 秒
    String message() default "请勿重复提交";
}
```

**实现**:`SETNX key value EX ttl`,命中即抛 `BizException(code=40900)`。
注意:锁座接口的 key 用 `sessionId + ':' + sorted(seats).join(',')` 才能精确到具体座位组合。

**应用点**:
- `OrderController.lock()` → `@Idempotent(key="#dto.sessionId+'-'+#dto.seatIndexes.![T(java.lang.Integer).valueOf(#this)].toString()", ttl=3)`
- `OrderController.pay()` → `@Idempotent(key="#orderNo", ttl=5)`

**验收**:
- 同 payload 锁座请求 2 次,只成功 1 次,第二次返 `code=40900`
- 3 秒后重试,正常进入业务(未真正"防"过期幂等,只防短时间重试)

### 3.5 N1 退票流程

**接口**:`POST /api/orders/{orderNo}/refund`

**流程**:
1. 校验:订单属于本人 + status=PAID + start_time > now(场次未开场)
2. CAS 0→3:`UPDATE order SET status=3, updated_at=now() WHERE order_no=? AND status=1 AND user_id=?`
3. 取座位列表(复用 `order.seatIndexCache`,避免再查 DB)
4. 调 `seatLuaService.releaseSeats`(已存在的 Lua,反向操作)
5. 调 `mockRefundService.refund(orderNo, totalAmount)` — 模拟退场,实际只 log + 写 `refund_log` 表
6. CAS 3→4:`UPDATE order SET status=4, refunded_at=now() WHERE order_no=? AND status=3`
7. 清理 `user:pending` / `user:locked` / `user:ticket` (N2)
8. `seatEventPublisher.publishReleased()` 广播
9. 退票成功返回

**退款日志表 `refund_log`**:
```sql
CREATE TABLE refund_log (
  id          BIGINT PRIMARY KEY,
  order_no    VARCHAR(32) NOT NULL,
  user_id     BIGINT NOT NULL,
  amount      DECIMAL(10,2) NOT NULL,
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0成功 1失败',
  reason      VARCHAR(255) NOT NULL DEFAULT '',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_no),
  KEY idx_user (user_id, created_at)
);
```

**补偿**:`OrderTimeoutCompensateJob` 顺带扫 `status=3 AND updated_at < now - 5min` 的"卡死"退票单,标 2 失败(降级,不入账)。

**验收**:
- 支付成功后 5 秒内可退
- 已开场场次不可退(返 42200)
- 退票后座位实时变可选(WebSocket 推 RELEASED)
- 退票后订单列表显示"已退款"

### 3.6 N2 电子票二维码

**生成**:
- 时机:支付成功后 (`OrderPayService.pay` 末尾)
- payload: `JSON({orderNo, userId, sessionId, exp: now+24h, nonce})`
- HMAC-SHA256(payload, secret) → sig
- base64url 编码整体
- 写 Redis:`cinema:ticket:{orderNo}` = base64, TTL = 24h

**接口**:
- `GET /api/orders/{orderNo}/ticket` → 返回 base64 + 过期时间
- `GET /api/tickets/verify?p=...&sig=...` (公开,免鉴权) → 校验并标已用

**前端**:
- 订单详情页 / 支付成功页增加"我的票"按钮
- 点击 → 弹层显示 QRCode(用 `qrcode` npm 包,客户端渲染, 不依赖第三方服务)
- 二维码下显示场次时间 + 座位 + 影院名

**验收**:
- 支付后订单详情有"我的票"按钮
- 二维码扫描能进 verify 端点
- 同一票二次 verify 返"已使用"
- 24h 后自动失效

### 3.7 O3 管理端经营看板

**页面路径**:`/admin/dashboard`(AdminHome 顶部加导航)

**指标(全部走聚合 SQL, 1 个 endpoint 返回所有)**:

| 指标 | SQL 思路 |
| --- | --- |
| 今日票房 | `SELECT IFNULL(SUM(total_amount),0) FROM \`order\` WHERE status=1 AND DATE(paid_at)=CURDATE()` |
| 今日订单数 | `SELECT COUNT(*) FROM \`order\` WHERE DATE(created_at)=CURDATE()` |
| 今日支付数 | `SELECT COUNT(*) FROM \`order\` WHERE status=1 AND DATE(paid_at)=CURDATE()` |
| 今日锁座数(待支付) | `SELECT IFNULL(SUM(seat_count),0) FROM \`order\` WHERE status=0 AND DATE(created_at)=CURDATE()` |
| 今日超时关单数 | `SELECT COUNT(*) FROM \`order\` WHERE status=2 AND DATE(updated_at)=CURDATE()` |
| 今日退票数 | `SELECT COUNT(*) FROM \`order\` WHERE status=4 AND DATE(updated_at)=CURDATE()` |
| 7 日票房趋势 | `SELECT DATE(paid_at) d, SUM(total_amount) a FROM \`order\` WHERE status=1 AND paid_at > now()-7d GROUP BY DATE(paid_at)` |
| Top5 影片(本周) | `SELECT m.title, SUM(o.total_amount) FROM \`order\` o JOIN session s ...` |
| 场次上座率(本周) | `SELECT s.id, m.title, s.start_time, SUM(seat_count)/hall.seat_count ...` |
| 锁座率(7 日) | 锁座订单 / 总订单 |

**接口**:`GET /api/admin/dashboard/summary` 返回整体统计 + 7 日趋势;`/top-movies`、`/top-sessions`。

**前端**:
- 顶部 4 张大数字卡片(今日票房/订单/支付/退票)
- 7 日票房趋势图(用 ECharts 折线)
- Top5 影片(柱状图)
- 场次上座率 TOP10(表格 + 进度条)

**验收**:
- 看板加载 ≤ 500ms
- 数据与 DB 直接查询一致(随机抽 3 个数对账)
- 移动端基本可读(>= 768px 正常, < 768px 降级为列表)

### 3.8 F1 电影搜索 + 筛选

**接口**:`GET /api/movies?keyword=xxx&genre=action&region=cn&status=1&page=1&size=20`

**SQL**:
```sql
SELECT * FROM movie
WHERE status = #{status}
  AND (title LIKE CONCAT('%', #{keyword}, '%') OR description LIKE CONCAT('%', #{keyword}, '%'))
  AND (#{genre} IS NULL OR genre = #{genre})
  AND (#{region} IS NULL OR region = #{region})
ORDER BY created_at DESC
LIMIT #{offset}, #{size}
```

**DB 变更**:
```sql
ALTER TABLE movie ADD COLUMN genre VARCHAR(32) NOT NULL DEFAULT '' COMMENT '类型';
ALTER TABLE movie ADD COLUMN region VARCHAR(16) NOT NULL DEFAULT '' COMMENT '地区';
ALTER TABLE movie ADD COLUMN release_date DATE NULL COMMENT '上映日期';
ALTER TABLE movie ADD INDEX idx_genre (genre);
ALTER TABLE movie ADD INDEX idx_release (release_date);
```

**前端**:
- Home.vue 顶部加搜索框 + 筛选弹层(类型/地区/年份)
- 搜索走前端 debounce 500ms
- 命中数 < 5 高亮显示
- 搜索结果复用现有 `movie-card` 渲染

**验收**:
- 搜索"地球"能命中"流浪地球3"
- 筛选"动作 + 中国"返回正确子集
- 空搜索 / 无结果有友好提示
- URL 携带 query 状态,可分享

### 3.9 D1 数据大屏(实时)

**页面路径**:`/admin/live`(AdminHome 加入口)

**布局**(全屏,1440x900 适配):
```
┌──────────────────────────────────────────────────┐
│  影院实时数据大屏     当前时间:2026-08-31 10:20  │
├──────────────┬──────────────┬─────────────────────┤
│ 今日票房 ¥  │ 今日订单 X  │ 在线用户 Y          │
│ 实时跳动     │              │ (WebSocket 推送)     │
├──────────────┴──────────────┴─────────────────────┤
│ 锁座实时事件流(滚动)         │ 票房趋势(折线)     │
│ [10:20:01] user1 锁座 sid=4 │ 12h 趋势          │
│ [10:20:03] user2 支付 ord..│                    │
├──────────────────────────────┴─────────────────────┤
│ Top 5 影片(柱状)        │ 上座率 TOP5(横向)     │
└──────────────────────────────────────────────────┘
```

**实时数据**:
- WebSocket 复用 `/ws/seat/{sessionId}`,但扩展 `/ws/admin` 频道
- 事件:`lock / pay / cancel / refund / new_session`
- 客户端订阅 `/ws/admin`,后端在 `seatEventPublisher` 之外加一个 `adminEventPublisher`
- 数字变化用 `requestAnimationFrame` 平滑过渡(避免硬切)

**前端**:新增 `views/admin/LiveDashboard.vue` + 用 ECharts(若 P0 期间没装则用 Chart.js 轻量替代)。

**演示要点**:开两个浏览器窗口,一个下单,另一个大屏秒级跳动 → 答辩装逼件。

**验收**:
- 打开页面 ≤ 1.5s 渲染
- 下单/支付事件 1s 内反映到大屏
- 关闭大屏不影响业务
- 至少连续运行 1h 无内存泄漏

---

## 4. 数据模型变更(集中 DDL)

```sql
-- ============================================================
-- P0 增量 - 数据库变更补丁
-- 执行: mysql -uroot -p cinema < sql/03_p0_increment.sql
-- 兼容: 已运行 01+02 的库可幂等执行(IF NOT EXISTS / ADD COLUMN 用 NULL 检测)
-- ============================================================

USE cinema;

-- F1: 电影扩展字段
ALTER TABLE movie
  ADD COLUMN genre        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '类型',
  ADD COLUMN region       VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '地区',
  ADD COLUMN release_date DATE         NULL COMMENT '上映日期',
  ADD INDEX idx_genre (genre),
  ADD INDEX idx_release (release_date);

-- N1: 退款日志
CREATE TABLE IF NOT EXISTS refund_log (
  id          BIGINT        NOT NULL PRIMARY KEY COMMENT '雪花ID',
  order_no    VARCHAR(32)   NOT NULL,
  user_id     BIGINT        NOT NULL,
  amount      DECIMAL(10,2) NOT NULL,
  status      TINYINT       NOT NULL DEFAULT 0 COMMENT '0成功 1失败',
  reason      VARCHAR(255)  NOT NULL DEFAULT '',
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_no),
  KEY idx_user (user_id, created_at)
) ENGINE=InnoDB COMMENT '退款日志';

-- N1: 订单表新增状态字段
ALTER TABLE `order`
  ADD COLUMN refunded_at DATETIME NULL COMMENT '退款完成时间' AFTER paid_at,
  ADD KEY idx_status_paid (status, paid_at) COMMENT '看板:按 status+paid_at 聚合';

-- N2: 票根(用于一次性扫码, 验票后置 verified_at)
CREATE TABLE IF NOT EXISTS ticket (
  order_no    VARCHAR(32)  NOT NULL PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  session_id  BIGINT       NOT NULL,
  payload     VARCHAR(512) NOT NULL,
  sig         VARCHAR(128) NOT NULL,
  exp_at      DATETIME     NOT NULL,
  verified_at DATETIME     NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user (user_id, created_at),
  KEY idx_session (session_id)
) ENGINE=InnoDB COMMENT '电子票';

-- O3/D1 看板: 已在 order 表加 idx_status_paid
-- (无新表)
```

---

## 5. API 表面(新增/修改)

| Method | Path | 用途 | 关联 | 限流/幂等 |
| --- | --- | --- | --- | --- |
| POST | `/api/orders/{orderNo}/refund` | 退票 | N1 | `@RateLimit` + CAS |
| GET | `/api/orders/{orderNo}/ticket` | 取票二维码 | N2 | `@Idempotent(ttl=10)` |
| GET | `/api/tickets/verify` | 验票(公开) | N2 | rate-limit-by-ip |
| GET | `/api/admin/dashboard/summary` | 经营看板数据 | O3 | admin-only |
| GET | `/api/admin/dashboard/top-movies` | TOP 影片 | O3 | admin-only |
| GET | `/api/admin/dashboard/top-sessions` | TOP 场次 | O3 | admin-only |
| POST | `/api/admin/bitmaps/recover` | 手动恢复 bitmap | P5 | admin-only |
| GET | `/api/movies` (改) | 加 keyword/genre/region 参 | F1 | 无 |
| GET | `/api/admin/live/stream` (WS) | 大屏实时事件 | D1 | admin-only |
| POST | `/api/orders/lock` (改) | 加 @RateLimit + @Idempotent | E4/E5 | 限流+幂等 |
| POST | `/api/orders/{orderNo}/pay` (改) | 加 @RateLimit + @Idempotent | E4/E5 | 限流+幂等 |

---

## 6. 前端页面/组件清单

| 路径 | 关联 | 备注 |
| --- | --- | --- |
| `views/admin/Dashboard.vue` (新) | O3 | 4 卡片 + 3 图 + 1 表 |
| `views/admin/LiveDashboard.vue` (新) | D1 | 全屏,WS 推送 |
| `views/MovieSearch.vue` (新,或合并到 Home) | F1 | 搜索框 + 筛选 |
| `views/OrderDetail.vue` (新,或改 OrderList 行) | N1/N2 | 退票按钮 + 二维码弹层 |
| `components/QrTicket.vue` (新) | N2 | 二维码渲染 + 倒计时 |
| `components/RateLimitToast.vue` (新) | E4 | 限流错误友好提示 |
| `router/index.ts` (改) | 全部 | 加新路由 |
| `api/admin.ts` (改) | O3/D1 | 加 dashboard / live 接口 |
| `api/order.ts` (改) | N1/N2 | 加 refund / ticket |
| `utils/ws.ts` (改) | D1 | 加 `/ws/admin` 频道 |

---

## 7. 风险与回退

| 风险 | 触发 | 缓解 | 回退 |
| --- | --- | --- | --- |
| 拆 `OrderService` 漏掉边界 | 编译过但行为变 | E2 关键路径单测先行 | 保留 `OrderService` `@Deprecated` 委托 3 个迭代 |
| bitmap 重建漏 race | 重建中被新锁座撞 | 用 Lua 原子脚本, lock 住再读 DB | 重建失败时仅 log, 不阻塞业务 |
| 限流误伤正常用户 | 退票按钮 1 次/分钟 太严 | 把 permits 调到合理值 | 关 Aspect 注解即可回退 |
| 二维码被截屏外传 | 唯一性 | 24h 过期 + 一次性 verify | 缩短 TTL 到 2h,加 user-agent 校验 |
| MySQL LIKE 慢 | 10w 行扫描 | 走前缀索引 + 分页 ≤ 50 | 加 ES / Meilisearch(P2) |
| 大屏 WS 风暴 | 1000 事件/秒 | 服务端节流(只推汇总) | 客户端本地聚合 |

---

## 8. 实施顺序(按依赖)

```
Day 1 (上午)
  E2 关键路径单测 ← 必须先有,否则 E1 拆完无验证手段
  E1 拆 OrderService  ← 单测保护下拆分
  P5 bitmap 冷启动重建  ← 独立,无依赖

Day 1 (下午) + Day 2
  E4 @RateLimit
  E5 @Idempotent
  F1 搜索 + 筛选 (小)

Day 2-3
  N1 退票 (依赖订单表新增字段 + refund_log)
  N2 二维码 (依赖 ticket 表)

Day 3-4
  O3 经营看板 (依赖 E1 拆完的 OrderQueryService)

Day 4-5
  D1 数据大屏 (WS 频道扩展)
  端到端联调 + 压测回归
```

> 估时按"熟悉 W1-W5 + Phase A-F"的开发者,首次做建议 +30%。

---

## 9. 验收标准(发布门)

1. **代码质量**:`wc -l` 每个 service ≤ 300 行;`OrderService.java` ≤ 50 行(只留委托)
2. **测试**:`mvn test` 全绿;关键 4 路径(锁/付/退/超时)各有 1 个 JUnit
3. **压测**:`test_mixed.py` 200 并发 30s,P99 不退化 ±10%
4. **功能**:9 项每项都有"打开页面能看到、点一下能演示"的产出
5. **答辩素材**:
   - 数据大屏能现场跑(`localhost:5173/admin/live` + 下单演示)
   - 经营看板有真实数据
   - 退票 + 二维码走完完整流程

---

## 10. 待补 / 留 hook(本 spec 不展开)

- E6 管理员操作审计:`AdminAction` 注解已留 Aspect 入口,本 spec 不填实现,后续 P1 接
- E7 OpenAPI 完善:本 spec 不动 swagger-ui
- O1 优惠券 / O2 会员积分:留 hook 在 `orderItem.price` 字段已能扩
- Q4 Prometheus 指标:Micrometer 接入本 spec 留 TODO,答辩后接

---

## 11. 文档元信息

- 评估日期:2026-08-31
- 关联:基于 `docs/superpowers/specs/2026-08-31-improvements-roadmap.md`(plan.md 的 P0 段)
- 下一阶段:用户 review 本 spec → 提反馈 → 进入 implementation(每项 1 个 implementation plan)

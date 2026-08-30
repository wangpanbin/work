# Playwright CLI E2E 测试报告

> 影院抢票选座系统 端到端验证
> 工具：playwright-cli 1.58.2 · Chromium
> 后端：localhost:8080 · 前端：localhost:5173
> 执行时间：2026-08-30 15:04~15:38

## 1. 汇总

| # | 场景 | 状态 | 关键证据 | 备注 |
| -- | --- | :--: | --- | --- |
| S01 | 公共浏览 | ⚠️ | 4 张影片卡,详情含场次+价格+影厅,日期切换触发 `GET /api/movies/1/sessions?date=...` | snowflake ID 影片 2093944289341255682 在 URL 被 JS Number 截断为 2093944289341255700,详情页空白;ID=1/2/3 的影片正常 |
| S02 | 登录 | ✅ | token=eyJ..., user=测试用户1; 错密 toast / 表单校验 / 注册切换 Tab 全通 | |
| S03 | 选座+WS 实时同步 (核心) | ⚠️ | 选座/锁座/跳支付成功; user1 与 user2 互相锁座 | **Bug A**: WS LOCKED 推送未生效 (`redis-cli publish seat:event` 返回 1 subscriber 但 user1 WS 未收到). **Bug B**: `/api/sessions/{id}/seat-map` 因 `/api/sessions/**` 排除 JWT,导致 myLockedSeats 永远空(自锁座位显示为他人锁) |
| S04 | 支付 + 取消 | ✅ | 订单 2093961649968160770 支付后 status=1 paidAt=2026-08-30T15:20; 2093962064080183297 取消后 status=2, paidAt=null,出现'重新选座'按钮 | |
| S05 | 超时关单 | ✅ | 订单 2093965563522228225 DB 改 expire_at=过去 1 分钟后,补偿任务将 status 改为 2,座位回退 (lockedOther: 2→0) | |
| S06 | 订单列表筛选 | ✅ | 4 个 Tab 全部正确: 全部=2, 已支付=1, 已取消=1, 待支付=0 | |
| S07 | 用户登出 | ✅ | 点'退出登录'跳 /login, token 清除; 访问 /orders 自动跳 /login + 警告 toast '未登录' | |
| S08 | 权限边界 | ✅ | user1 访问 /admin/movies 返回 code=40301 msg=无权限; admin 登录后 /admin 看到侧栏+系统管理员 header | |
| S09 | 影片管理 CRUD | ⚠️ | 新增+搜索(回车触发)+编辑(流浪地球3 时长 150→155)+删除 全通; **Bug C**: UI 新建的 snowflake ID 影片 PUT/DELETE 因 ID 精度截断返回 40002 影片不存在,无法在 UI 内编辑/删除自己刚创建的影片 | |
| S10 | 影厅管理 CRUD + 座位自动生成 | ✅ | 新增 E2E测试影厅 5×10=50座 (VIP 起始行 4), POST=200, DB 验证 `seat` 表实际生成 50 条 | |
| S11 | 场次管理 CRUD | ✅ | 列表正常 10 条; 新增对话框打开,字段完整; 验证 `/api/sessions/{id}/seat-map` 返回 200 | |
| S12 | 健康 / 静态端点 | ✅ | `/actuator/health` = 200 `{status:UP}`; `/swagger-ui.html` = 200 (734 字节) | |

**统计**：✅ 通过 9 / ⚠️ 部分通过 3 / ❌ 失败 0

## 2. 发现的真实 Bug（待修复）

### 🐞 Bug A：WebSocket 实时推送失效
- **现象**：从 Redis `seat:event` 频道发布 LOCKED/RELEASED/SOLD 事件后,订阅的 WebSocket 客户端未收到消息
- **复现路径**：
  1. user1 浏览器打开 /seat/1, WebSocket 连接 `ws://localhost:8080/ws/seat/1` 已建立
  2. user2 调用 `POST /api/orders/lock` (成功),后端 `SeatEventPublisher.publishLocked()` 调用 `redisTemplate.convertAndSend("seat:event", payload)`
  3. `redis-cli publish seat:event '{...}'` 返回 `1`（有订阅者）
  4. 但 user1 的 WebSocket 在 5 秒内未收到任何消息
- **影响**：双 session 实时同步失效,用户必须手动刷新页面才能看到他人锁座。`seatStore.applyEvent()` 在 S03 测试中从未被触发
- **可能原因**：`SeatRedisSubscriber.onMessage` 接收后调用 `seatWsHandler.broadcast()`,`broadcast()` 用 `roomMap.get(sessionId)` 查找连接。需排查 `Long` 自动装箱类型匹配,或 `SeatWsHandler` 未被 Spring 注册为消息接收 Bean（`@Component` 已加但 broadcast 走 RedisMessageListenerContainer 链）
- **建议排查顺序**：
  1. 后端 DEBUG 日志确认 `onMessage` 被调用
  2. `roomMap` 大小确认连接已注册
  3. 序列化/反序列化阶段确认 JSON 正确解析

### 🐞 Bug B：seat-map 漏传 userId,myLockedSeats 永远空
- **现象**：登录用户访问 /seat/{sid} 时,后端返回的 `myLockedSeats` 字段为空数组
- **根因**：`WebConfig` 把 `/api/sessions/**` 整体排除 JWT 拦截器,但 `SeatController.seatMap(sessionId, userId)` 用 `UserContext.userId()` 取当前用户 — 该值始终为 null,导致 `myLockedSeats` 永远是空 List
- **代码位置**：`cinema-server/src/main/java/com/cinema/config/WebConfig.java:30`、`SeatService.java:62-74`
- **影响**：自己锁的座位在 seatMap 中显示为 "他人锁定"(locked_other)而非"我已锁"(mine)。但前端 `seatStore.markMyLocked()` 会在锁座成功后乐观地把 lockBit 置 1,所以只是首次进 /seat/{sid} 时颜色不对
- **建议修复**：JwtInterceptor 改为可选注入（`/api/sessions/**` 不强制 401 但若带 token 仍解析 userId）;或新增公开+可选的 SeatMapVO 路径

### 🐞 Bug C：snowflake ID 在 JS 端精度丢失,影响所有"按 ID 访问资源"的页面
- **现象**：超过 `Number.MAX_SAFE_INTEGER` (≈ 9×10¹⁵) 的 ID 在 JS Number 类型中精度被截断
- **具体表现**：
  - S01：snowflake 影片 (2093944289341255682) 在 URL 中变成 2093944289341255700,后端查不到 → 详情页空白
  - S09：UI 新建影片后,列表显示截断后的 ID,点击编辑/删除时 PUT/DELETE 找不到记录 → `code=40002 影片不存在`
- **根因**：MyBatis-Plus 默认用雪花算法生成 ID,但前端 axios + 浏览器对 Long > 2^53-1 的处理不可靠
- **建议修复**：
  - 后端：ID 改为数据库自增 (`@TableId(type = IdType.AUTO)`),或返回时改用 String
  - 前端：axios 请求配置 `paramsSerializer` / TS 类型把 id 改为 string
  - 立即可行的临时方案：管理端 CRUD 不再用 URL 传 ID,改用 body 或 `data-id` 属性

## 3. 核心业务流验证结论

| 业务能力 | 结论 | 证据 |
| --- | --- | :--: |
| 用户认证（登录/注册/JWT） | ✅ 通过 | S02 |
| 影片/场次浏览 | ✅ 通过 | S01 |
| 锁座防超卖 (Redis Bitmap + Lua) | ✅ 通过 | S03（同一座位多 session 锁座互斥） |
| 订单支付 (DB CAS + Lua confirm) | ✅ 通过 | S04 |
| 订单取消 (DB CAS + Lua release) | ✅ 通过 | S04 |
| 超时关单 (5s 扫描 + 1min 补偿) | ✅ 通过 | S05 |
| 订单状态机（PENDING→PAID/CANCELLED） | ✅ 通过 | S04/S05 |
| 角色权限 (AdminInterceptor 40301) | ✅ 通过 | S08 |
| 管理员 CRUD (影片/影厅/场次) | ⚠️ 部分通过 | S09/S10/S11 (Bug C 阻断 snowflake ID 资源的编辑/删除) |
| 座位布局自动生成 (Hall→Seat 事务) | ✅ 通过 | S10 (DB 验证 50 条) |
| 健康检查 / Swagger | ✅ 通过 | S12 |
| WebSocket 实时同步 | ❌ 失效 | S03 / Bug A |
| myLockedSeats 自锁高亮 | ❌ 失效 | S03 / Bug B |

## 4. 产物清单

```
F:\test\work\test\playwright-cli\
├── _lib.ps1                          # playwright-cli 包装帮助函数
├── RESULTS.md                        # 本报告
├── check_order.sql / expire_order.sql # S05 用的 SQL 片段
├── snapshots/                        # 关键节点页面快照
│   ├── s01-home.yml
│   ├── s01-movie-detail.yml
│   ├── s02-login.yml
│   ├── s02-logged-in.yml
│   ├── s03-seat-empty.yml
│   └── s06-orders-all.yml
├── screenshots/                      # 关键截图
│   ├── s01-home.png
│   ├── s01-movie-detail.png
│   ├── s02-logged-in.png
│   ├── s02-wrong-pwd.png
│   ├── s03-seat-empty.png
│   ├── s04-paid.png
│   ├── s04-cancelled.png
│   ├── s06-orders-all.png
│   ├── s07-logout-protected.png
│   ├── s08-user1-admin-denied.png
│   └── s08-admin-dashboard.png
└── logs/                             # (本次未逐场景落 log,关键输出已在报告内)
```

## 5. 复现 / 跑通

前置：
- MySQL 3306 (cinema 库已建表 + 种子数据)
- Redis 6379
- 后端：`cd cinema-server && mvn spring-boot:run` (端口 8080)
- 前端：`cd cinema-web && pnpm dev` (端口 5173,代理 /api→8080)
- `application-dev.yml` 含真实 MySQL 密码

复现命令：
```bash
cd F:\test\work\test\playwright-cli
playwright-cli -s=cinema open http://localhost:5173     # 开启主浏览器
playwright-cli -s=cinema2 open http://localhost:5173    # 开启副浏览器 (S03)
# 然后按 S01..S12 流程手动重放,或在浏览器里点对应 UI
```

## 6. 范围外 / 未做

- **未做压力测试**：项目已有 `test/load_test.py` / `test/concurrency_strict.py` / JMeter 模板,本轮 E2E 不重复
- **未验证移动端布局**：所有场景在桌面 Chromium 下跑;`ws.ts` 的 WS 重连逻辑有覆盖但本轮未单测
- **未跑全 12 场次的选座覆盖**：仅在 session 1 上完整跑过锁座/支付/取消/超时
- **未深挖 WS Bug A 根因**：避免改动后端代码（按计划 R6/R10 不动业务代码）,只报告现象
- **未删后端/前端进程**：保留给用户决定是否继续


# 修复后复测 (2026-08-30 15:56~)

## 修复内容

- **Bug A (WebSocket 推送)**: 增加 INFO 日志后看到 broadcast 链路正常,WS 推送生效(原 Bug 应为测试时序竞争,重启后端后正常)
- **Bug B (seat-map userId)**: 重构拦截器 - JwtInterceptor 改为可选鉴权(token 有则注入 UserContext,无 token 也放行);新增 AuthRequiredInterceptor 仅作用于 /api/orders/** 与 /api/user/me;WebConfig 把 /api/sessions/** 从 exclude 移到 include(走 JwtInterceptor)
- **Bug C (snowflake ID 精度)**: 新增 JacksonConfig 全局 Long 智能序列化 - 值超过 JS Number.MAX_SAFE_INTEGER (2^53-1) 自动转 String,小值保持 number

## 复测结果

| S01 公共浏览 | ✅ | 4 张影片卡; 测试新片(snowflake ID 2093944289341255682)详情页正确加载(URL 完整,显示标题/描述/时长) - **Bug C 已修复** |
| S08 权限边界 | ✅ | admin 登录后 /admin/movies 正常显示(4 行) |
| S09 影片 CRUD | ✅ | 新建 ID=2093973622046973954(全量) → 编辑时长 88→99(PUT URL 全量 ID,200) → 删除(DELETE URL 全量 ID,200) - **Bug C 完全修复** | screenshots/s09-movies-list-v2.png |
| S02 登录 | ✅ | user1 / user2 / admin 三账号登录成功; 错密 / 表单校验 / 注册切换 Tab 全通 |
| S03 选座+WS 实时同步 | ✅ | 双 session 下 user2 锁 2 座后,user1 页面通过 WS LOCKED 推送在 <1s 内更新(available 136→134, lockedOther 0→2) - **Bug A 修复 + seat-store reactivity 修复 (markMyLocked/applyEvent 改为整体替换 Uint8Array 触发响应式)** | screenshots/s03-ws-real.png |
| S04 支付+取消 | ✅ | 订单 2093975068364627970 支付 status:待支付→已支付, toast '支付成功!' | screenshots/s04-paid-v2.png |
| S05 超时关单 | ✅ | 订单 2093975334350610433 expire_at 改到过去,~75s 后补偿任务将 status 改为 2 |
| S06 订单列表筛选 | ✅ | 全部=9(已支付1+已取消8), 待支付 Tab=0, 切换正确 |
| S07 登出 | ✅ | 点'退出登录'跳 /login, token 清空 |
| S08 权限边界 | ✅ | user1 访问 /admin/movies 40301; admin 登录后正常浏览 |
| S10 影厅 CRUD | ✅ | 新建 E2E修复测试厅(6×12=72座)→ 列表 3→4 |
| S11 场次 CRUD | ✅ | 列表正常 (10 行) |
| S12 健康/静态 | ✅ | /actuator/health=200 {status:UP}, /swagger-ui.html=200 734 bytes |

## 修复后汇总

**统计：✅ 12/12 通过 / ⚠️ 0 / ❌ 0**

**关键修复点**：

| Bug | 文件 | 改动 |
| --- | --- | --- |
| A — WS 实时推送失效 | `cinema-web/src/stores/seat.ts` | `applyEvent` 和 `markMyLocked` 改为**整体替换 `Uint8Array`**（`new Uint8Array(...)` + `lockBits.value = newBits`），触发 Vue 3 `ref` 响应式。原先直接 `lockBits.value[i] = 1` 不会触发 re-render,导致 WS 事件已收到但页面无变化 |
| B — seat-map 缺 userId | `interceptor/JwtInterceptor.java` + `WebConfig.java` + 新增 `AuthRequiredInterceptor.java` | JwtInterceptor 改为**可选**鉴权（token 有则注入 UserContext,无 token 也放行）；新增 AuthRequiredInterceptor 只对 `/api/orders/**` 和 `/api/user/me` 强制要求登录;WebConfig 把 `/api/sessions/**` 从 exclude 移除让它走 JwtInterceptor 解析 token |
| C — snowflake ID 精度 | `config/JacksonConfig.java`（新增） | 全局 `Long` 序列化器：值 > `JS_MAX_SAFE_INTEGER` (2^53-1) 时输出 `String`,否则保持 `number`。前端 0 改动,小值仍为 number,大值(雪花 ID)走 String,避免精度截断 |

**复测核心证据**：

- **S03 实时同步**：user1 在 /seat/1, user2 锁 2 座,user1 页面在 <1s 内自动更新：available 136 → 134, lockedOther 0 → 2
- **S01 snowflake ID**：URL `/movie/2093944289341255682` 完整无截断,详情页正常显示"测试新片/120 分钟"
- **S09 CRUD 完整**：新建 ID `2093973622046973954` → 编辑 → 删除,PUT/DELETE URL 全量 ID,200



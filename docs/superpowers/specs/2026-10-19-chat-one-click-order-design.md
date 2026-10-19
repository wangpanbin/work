# 对话助手「一键下单」设计 Spec — v0.2.0

- 日期:2026-10-19
- 上游:ADR-0004(`docs/adr/0004-chat-order-proposal.md`)、`docs/superpowers/specs/2026-09-21-chat-assistant-design.md`
- 状态:已实现

## 0. 一句话决策

助手可以输出一种新的行动卡片 `ORDER_PROPOSAL`:模型负责**备单**(场次 + 张数 + 座位建议),服务端**重读位图核验并生成快照**,用户在对话里点一次「确认下单」即锁座建单(进入待支付),付款仍在支付页由用户自己完成。

## 1. 背景与问题

v0.1.0 的助手只输出只读建议:用户说"帮我订明天那场《X》的 2 张连座",助手算好座位给出 `SEAT_SUGGESTION` 卡,点一下跳到 `/seat/:sessionId?preselect=...`,用户**还要自己在座位图上重新确认一次**才能锁座。

意图已经在对话里说清楚了,却要用鼠标重做一遍 —— 这是本轮要消掉的摩擦。

但消掉它的前提是给 chat 模块开写路径,而 ADR-0002 明确禁止过这件事(理由见 ADR-0002 背景的四条代码级证据)。因此本 spec 的重点不是"怎么加写工具",而是**"怎么在推翻面最小的前提下加一条安全的写路径"**。

## 2. 目标与非目标

### 2.1 目标

1. 用户一轮对话内完成"选座 → 锁座建单",**只点一次**;
2. 锁座用到的座位与价格**全部由服务端核验**,模型说的只是提示;
3. 副作用(取消同场次旧待支付单)对用户**可见**;
4. 付款这一步**始终由用户自己完成**;
5. 整条写路径**可回滚**(一个配置开关)、**可断言**(幂等键来自服务端快照)。

### 2.2 非目标

- 不做代付、不接真实支付(沿用 P0 spec 非目标)
- 不做机器自动换座重试(ADR-0002 证据 3)
- **不新增第 9 个 `@Tool`** —— `ChatTools` 与 `ChatToolsStructureTest` 一字不改
- 不改 `POST /orders/lock` 及其 `@Idempotent` / `@RateLimit`
- 不改 `/seat-map` 限流策略(会污染已压测的 P99 基线)
- 不新增 MySQL 表(提案是 90 秒临时态)
- 不做流式输出、不做 RAG、不做对话历史落库

## 3. 架构

### 3.1 组件与调用关系

```
LLM(CinemaAssistant)
  │  只读工具: listSessions / findContiguousSeats / ...
  │  输出 ```json-cards {cards:[{type:"ORDER_PROPOSAL", sessionId, count, seatIndexes, ...}]}
  ▼
ReplyCardParser.parse()                 按卡型分派必填规则
  ▼
OrderProposalAssembler.assemble()       ★ LLM 之外的安全闸门
  │  1. 开关关 / 未登录 / 场次非法 → 丢卡 + 在回复末尾追加说明
  │  2. OrderProposalService.createProposal()
  │       ├─ requirePurchasableSession(sessionId)     复用 OrderCore
  │       ├─ seatService.seatMap(sessionId, userId)  重读实时位图
  │       ├─ 模型座位三重校验(范围 / 可选 / 同行连座), 不通过则服务端重找(优先中段)
  │       ├─ requireSeatsInRange(sessionId, seats)   越界防护
  │       ├─ 价格取 session.getPrice()                丢弃模型报价
  │       ├─ findUserPendingOrder(userId, sessionId) 存量待支付单(D7)
  │       └─ Redis SET cinema:chat:proposal:{id} TTL 90s
  │  3. 用服务端值覆写卡片 + 填 proposalId / expiresAt
  ▼
ChatResponseVO{reply(+说明), cards(带 proposalId), followUps}
  ▼
前端 ChatMessage.vue 按 card.type 分发
  ├─ ORDER_PROPOSAL → OrderProposalCard.vue  倒计时 + 确认 + 重新找一组
  ├─ PENDING_ORDER  → PendingOrderCard.vue   订单号 + 支付窗口 + 去支付
  └─ 其它           → ActionCard.vue         原有跳转行为, 零改动
  ▼ 用户点「确认下单」
POST /api/chat/orders/{proposalId}/confirm
  ├─ @Idempotent(key="#proposalId", ttl=8)     幂等键 = 提案, 不是座位组合
  ├─ @RateLimit(3 次/分钟)
  ├─ AuthRequiredInterceptor → 未登录 40101
  ├─ consumeProposal(): 原子 GETDEL 读出即删 + 归属校验
  └─ OrderLockService.lockSeats(userId, dto)  复用既有写入口
  ▼ 成功
锁座 → 建待支付单 → 15 分钟延迟关单 → 座位实时广播 → 聊天内追加「待支付卡」
```

### 3.2 为什么提案放在 LLM 之外(后处理装配层)

给 `ChatTools` 加第 9 个 `@Tool` 更贴合 LangChain4j 的原生编排, 但会让 `ChatToolsStructureTest` 的「数量==8」与「名字集合==白名单」两条断言必须改, ADR-0002 的「助手不注册任何写工具」被字面推翻。

后处理装配层能达到同样效果:
- `ChatTools` 仍是 8 个只读工具, 反射白名单断言**一字不改**;
- 装配是**纯服务端确定性代码**, 不含 LLM 依赖, 可精确单测;
- 模型的幻觉在这里被拦住 —— 它甚至不需要"调用工具"这个能力就能出单。

### 3.3 提案快照

`OrderProposal`(存 Redis `cinema:chat:proposal:{proposalId}`, TTL 90s):

| 字段 | 说明 |
|---|---|
| `proposalId` | UUID, 幂等键来源 |
| `userId` | 归属绑定, 防 IDOR |
| `sessionId` / `seatIndexes` | 服务端核验过的场次与座位 |
| `unitPrice` / `totalAmount` | 取自 session 表, **不是模型给的** |
| `movieTitle` / `hallName` / `startTime` / `seatDesc` | 展示用 |
| `expiresAt` | 过期时刻(epoch ms), 卡片倒计时用 |
| `pendingCancelOrderNo` / `pendingCancelExpireAt` | 同场次存量待支付单 —— 确认后会被取消, 必须提前告知 |

**提案不占位**:TTL 内座位**不保证可用**。占位只发生在 `lockSeats` 那一刻。这与 `CONTEXT.md` 的「选座是意图,锁座是事实」「座位关注从不代替用户发起锁座」一致。

## 4. 座位选择算法

```
输入: userId, sessionId, count(1~4), preferredSeats(模型提示, 可空)
1. requirePurchasableSession(sessionId)          场次存在 + 在售 + 未开场
2. seatMap = seatService.seatMap(sessionId, userId)   实时位图
3. available[i] = !(lock 位) && !(sold 位)        任何人锁的都算不可选
4. 若 preferredSeats 全部 ∈ available 且同行连座 → 采纳
   否则 → 服务端重找:
        遍历每一行的连续可用段, 按 |行-中行|*cols + |中列-中列| 打分取最优
5. 仍找不到 → 业务错误「该场次没有 N 个连座可选」
6. requireSeatsInRange(sessionId, seats)         越界防护(防 Redis 位图被 SETBIT 撑爆)
```

**与 `ChatTools.findContiguousSeats` 的差别**:后者取**第一行**的连座且用 `List.contains` 做 O(n) 扫描;新算法遍历所有行、同分时取更居中的段,O(rows × cols × count)。既有工具**不修改**(它只读且被既有测试覆盖)。

## 5. API 契约

### 5.1 `POST /api/chat/orders/proposals` — 重新找一组

- 鉴权:必须登录(40101)
- 限流:10 次/分钟
- 请求体:`{ sessionId: Long, count: 1..4 }` —— **没有座位字段**
- 响应:`OrderProposalVO`

### 5.2 `POST /api/chat/orders/{proposalId}/confirm` — 确认下单

- 鉴权:必须登录(40101)
- 幂等:`@Idempotent(key="#proposalId", ttl=8)` → 重复点返 40900
- 限流:3 次/分钟
- 请求体:**无**(座位只从服务端快照取)
- 响应:`OrderProposalVO`(带 `orderNo` 与 `orderExpireAt`)
- 错误:40002 座位被抢 / 业务错误 提案过期 / 40301 提案属他人

### 5.3 错误码在前端的表现

| 码 | 表现 |
|---|---|
| 40002 | 卡内状态条「这几个座位刚被抢走了」+「重新找一组」按钮(**不自动重试**) |
| 40900 | 「下单请求处理中,请勿重复点击」 |
| 42900 | 「操作过于频繁,请稍后再试」 |
| 40101 | 拦截器清 token 并跳登录; 卡内显示「请先登录后再下单」 |
| 40301 | 「无权操作该下单方案」 |

**按 `err.code` 判断, 不按中文字符串** —— 既有 `SeatSelect.vue` 用字符串匹配是历史包袱, 新代码不继承。

## 6. 前端设计

### 6.1 卡型分发

`ChatMessage.vue` 按 `card.type` 分发; `ActionCard.vue` **保持零改动** → `tests/components/chat/ActionCard.test.ts` 6 条用例不受影响。

v-for 的 key 从 `${card.sessionId}-${idx}` 改成 `card.cardId ?? ${card.sessionId}-${idx}` —— 装配器丢卡后索引会位移, 用稳定 key 避免 Vue 复用错 DOM。

### 6.2 纯函数下沉

vitest 跑在 `environment: 'node'`, 没有 jsdom / `@vue/test-utils`, 挂不上 SFC。因此倒计时、过期判定、请求体拼装全部下沉到 `components/chat/orderProposalCard.ts`(纯函数, 无 Vue 依赖), 单测见 `tests/components/chat/orderProposalCard.test.ts`。

### 6.3 确认卡

- 展示服务端核过的座位号、单价、**总价**、**倒计时**(90s TTL)
- **警告行**:同场次有存量待支付单时显式列出订单号(D7)
- 提示行:「确认前座位不保证可用,确认后为你锁定 15 分钟」
- 主按钮「确认下单」/ 次按钮「重新找一组」
- 过期后按钮置灰 + 提示重新发起

### 6.4 待支付卡

锁座成功后聊天内**追加**(不自动跳转, 用户可能还想接着聊):订单号 / 座位 / 金额 / 支付窗口剩余分钟 + 「去支付」按钮 → `router.push({name:'payment', query:{orderNo}})`。

`/payment` 路由下聊天浮窗按 `useChatContext.isChatHiddenRoute` 自动收起(既有行为), 不遮挡支付页。

## 7. 配置与开关

```yaml
cinema:
  chat:
    order-enabled: ${CINEMA_CHAT_ORDER_ENABLED:true}        # 回滚闸门
    proposal-ttl-seconds: ${CINEMA_CHAT_PROPOSAL_TTL_SECONDS:90}
```

`order-enabled=false` → 装配器不建任何提案, 两个端点返业务错误。**不发版即可让整条写路径失效。**

## 8. 测试与验收

### 8.1 后端(JUnit5 + Mockito)

- `OrderProposalServiceTest`(15):座位以服务端为准 / 失效重找 / 越界防护 / 价格取 session / 存量待支付单 / TTL / 一次性消费 / IDOR 拒绝
- `OrderProposalAssemblerTest`(7):既有卡原样透传 / 服务端值覆写 / 失败丢卡 / 未登录 / 开关关闭
- `ChatOrderControllerTest`(9):座位来自快照 / 过期 / 开关关闭 / 未登录 / 注解门禁反射断言
- `ReplyCardParserTest`(+4):`ORDER_PROPOSAL` 的 count 必填、seatIndexes 可空、count 越界丢卡
- `ChatToolsStructureTest`(**未修改, 4 条断言全绿**)—— 「未新增写工具」的门禁

### 8.2 前端(vitest)

- `tests/components/chat/orderProposalCard.test.ts`(新):卡型判定 / 剩余秒数 / 三态 / 倒计时格式 / 请求体不含座位 / 支付路由
- `key-coverage.test.ts` + `locales-shape.test.ts`:新 i18n key 双语同步
- `themeTokens.test.ts`:新组件无硬编码色值

### 8.3 验收门禁

1. `mvn test` / `pnpm test` / `pnpm type-check` / `pnpm build` 全绿
2. **ADR-0002 门禁仍成立**:纯聊天(不点确认)后 `cinema:session:*:lock` 与 `cinema:user:pending:*` **零变化**
3. 提案 90s 后自动消失; confirm 后立即消失
4. 用 user2 的 token confirm user1 的 proposalId → 被拒
5. 越界座位绝不落进 `lock_seat.lua` 的 SETBIT
6. 浏览器走完「聊天 → 确认卡 → 确认 → 待支付卡 → 支付页 → 支付成功」全链路

## 9. 风险与回滚

| 风险 | 缓解 |
|---|---|
| LLM 不按格式出卡 | 装配器容错(丢卡 + 说明), 功能退化成旧的只读行为 |
| 模型幻觉价格 | 价格一律取 session 表 |
| 模型幻觉座位 / 越界 | 三重校验 + 服务端重找 + `requireSeatsInRange` |
| 用户误点丢旧单 | 确认卡显式列出待取消订单号 |
| 提案被冒用 | 绑定 userId + 确认时比对 |
| 既有抢票链路被波及 | `OrderLockService` 只提一个方法为 public, `POST /orders/lock` 完全不动 |

**回滚**:① `order-enabled=false`(不发版) ② 删 `modules/chat/order/` + 前端新组件(ADR-0001「回滚成本接近零」)。

## 10. 估时(实测)

| 阶段 | 内容 |
|---|---|
| 后端骨架 | RedisKeys / OrderProposal / OrderProposalService / findUserPendingOrder 提 public |
| 装配层 | ActionCardVO / ReplyCardParser / OrderProposalAssembler / ChatAssistantService 接入 |
| 端点与提示词 | ChatOrderController / WebConfig 鉴权 / 配置开关 / CinemaAssistant 提示词 |
| 前端 | 纯函数模块 / 两个新组件 / ChatMessage+ChatWidget 接线 / api / i18n 双语 |
| 文档与版本 | ADR-0004 / ADR-0002 标记 / 本 spec / 发布说明 / CONTEXT / AGENTS / README / 版本号 |

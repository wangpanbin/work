# 对话式订票助手 (chat assistant)

只读工具 + 一键下单提案(ADR-0004)的全部不变式与踩坑点。

> **硬约束仍在 `AGENTS.md`** —— 本文件是专题展开, 不覆盖仓库级底线。
> 改动前先读 `AGENTS.md` 的 `Conventions & gotchas` 一节。

## 提示词 ↔ 卡片契约(2026-10-25 修,附守卫测试)

`CinemaAssistant` 的 `@SystemMessage` 与 `ReplyCardParser` / `OrderProposalService` /
前端 `buildRepickRequest` 必须对同一件事说同样的话。这三处不在同一个文件里,
靠人评审抓不到彼此的漂移 —— 所以有一条测试把它们锁在一起(`CinemaAssistantPromptTest`)。

**2026-10-25 实测踩到的坑**:提示词写着「出 `ORDER_PROPOSAL` 卡**之前必须先调
`findContiguousSeats`** 拿座位索引, 拿不到就不出卡」, 而解析器 / 服务端 / 前端
**全都把 `seatIndexes` 当成可选**(ADR-0004 决策 D8:座位由服务端重读实时位图决定)。

后果是出卡链路**死锁**:模型要先有座位才能出卡 → 要先定场次 → 一晚有多个候选场次时
它就反问「你打算看哪一场」→ 永远走不到下单卡。浏览器回归 S22 因此长期停在 SKIP
(实测:三次真实调用全部返回 markdown 表格 + 反问,`cards=0`)。

修好的约定(改提示词时三条都不能破):

| 约定 | 说明 |
| --- | --- |
| `seatIndexes` **可选** | 服务端会重读实时位图自己挑连座, 模型不必先查座位 |
| **不要反问** | 用户已表达下单意图时, 场次自己挑(有余座优先)、张数按措辞推断 |
| 张数缺省 **2 张** | 用户完全没提张数时按 2 张出卡, 不为这个反问 |

`CinemaAssistantPromptTest` 三条断言分别锁:卡型集合与 `ActionCardVO` 常量一致、
`seatIndexes` 被声明为可选且提示词里不得出现「必须先调」、
「不要反问」与「默认给 2 张」还在。**改提示词后先跑它** ——
负向验证已做过:把提示词还原成 2026-10-25 之前的版本,前两条会红。

## 假 LLM(stub provider)—— 让「一键下单」这条验收可回归

`cinema.chat.provider`(`application.yml`,env `CINEMA_CHAT_PROVIDER`)两个取值:

| provider | 行为 |
| --- | --- |
| `deepseek`(默认) | 真模型。`DEEPSEEK_API_KEY` 非空才注册 `ChatModel` Bean |
| `stub` | `StubCinemaAssistant` —— 确定性回复, **不调用任何外部模型, 也不需要 api-key** |

**它只替换「模型决策」这一步**。卡片解析、提案装配、座位重读、价格核算、前端渲染
全是生产代码,走的是与真模型**完全相同**的那条路 —— 所以它不是 mock:契约变了它一样会炸
(`StubCinemaAssistantTest` 的断言直接走真的 `ReplyCardParser`)。

**为什么需要它**:一键下单确认卡(AC-20 那条浏览器链路)在真模型下「出不出卡」取决于
模型当天怎么发挥, 于是 S22 长期停在 SKIP。stub 让它确定性地 PASS。

**stub 挑场次的三个条件**(踩过坑):有余座 · 有连座(`longestContiguousLength` ≥ count)·
**还没开场** —— 已开场的场次下单会被服务端拒(`场次已开场,无法购票`),
那时卡片已经被装配器丢掉了, 用户只看到一句没头没尾的说明。
张数:消息里明说的优先,没明说按 **2 张**(与提示词一致)。

`GET /api/chat/status` 返回 `{"provider":"deepseek"|"stub","enabled":bool}`
(只读、公开、不含任何凭据)。浏览器回归 S22 读它决定定性:
**stub 模式下必须出卡, 没出卡记 FAIL**;真模型下没出卡才记 SKIP。

## 写审计与用量(v0.4.0,IT-05 / IT-11)

助手对话与「确认下单」都会往 `chat_assistant_log` 落一行。两张表共用一张:

| 口径 | `kind` | 关键列 | 谁看 |
| --- | --- | --- | --- |
| 写审计 | `WRITE_CONFIRM` | `confirmed_by_user`(1=用户显式确认)· `proposal_id` · `order_no` · `result` | 管理端看板 + 红线查询 |
| 用量 | `CHAT` | `latency_ms` · `prompt_tokens` · `completion_tokens` · `cost_cents` | 管理端看板 |

**三条不能破的语义**:

1. **审计写失败绝不影响用户下单** —— `ChatAssistantLogService.safeInsert` 全程 try/catch,
   失败只记 error 日志。审计挂掉就让下单失败是不可接受的。
2. **用量未知记 `null`,不记 0** —— 0 会被误读成「这次调用免费」。
   `tokenUsage()` 拿不到就写 null;单价未配置(配成 0)时 `cost_cents` 也是 null。
   看板的聚合用 `SUM(cost_cents)`(不是 `COALESCE(...,0)`)—— 否则「未配置单价」
   会被压成 0,那个状态就再也显示不出来了。
3. **成本是估算值,不是财务口径** —— 单价在 `application.yml` 的 `cinema.chat.cost.*`
   (美分 USD / 每百万 token)。DeepSeek 会调价且分峰谷时段 → **单价需人工维护**。
   取价来源:https://api-docs.deepseek.com/quick_start/pricing

**红线指标**:`SELECT COUNT(*) FROM chat_assistant_log WHERE kind='WRITE_CONFIRM' AND confirmed_by_user=0`
恒为 0。它恒为 0 的结构性理由:助手没有任何写工具(`ChatTools` 全是只读 `@Tool`),
写入口只有 `ChatOrderController#confirm` 一处,且那一处恒传 `confirmed_by_user=1`。
**将来新增任何写入口,必须显式传 1** —— 否则这条查询会立刻把它数出来。
`tools/run_all_gates.py` 的「审计红线」闸门查的就是它。

## 核心不变量与历史踩坑点

Design spec: `docs/superpowers/specs/2026-09-21-chat-assistant-design.md` + `docs/superpowers/specs/2026-10-19-chat-one-click-order-design.md`。It rides LangChain4j inside `cinema-server` (ADR-0001) and deliberately does not use RAG (ADR-0003). There are **8 `@Tool` methods** on `ChatTools` (4 movie/session + 2 seat + 2 order); `ChatToolsStructureTest` is the reflection whitelist that fails if any write method ever gets annotated.

**v0.2.0 起助手可以一键下单**(ADR-0004,局部修订 ADR-0002 —— 原文保留,只加修订标记)。三条不变量,改这块之前先确认没破:

- **助手仍不注册任何写工具** —— `ChatTools` 仍是 8 个只读 `@Tool`,`ChatToolsStructureTest` 的 4 条断言一字未改。写路径全在 LLM 之外的**后处理装配层**(`OrderProposalAssembler`)。
- **付款仍在用户手里** —— 助手只锁座建单到「待支付」,支付在 `/payment` 页由用户自己完成。
- **不做机器自动换座重试** —— 40002 座位被抢时只告知 + 给「重新找一组」按钮,把重试的决定权交回用户。

chat 模块第一次有了写操作,以下几条是踩坑点:

- **幂等键 = `proposalId`,不是 `userId:sessionId:seatIndexes`** —— 座位列表从不进幂等键,模型的幻觉/重试产生不了第二把钥匙(这正是 ADR-0002 证据 2 的正面回应)。
- **提案 = 服务端快照**(Redis `cinema:chat:proposal:{id}`,TTL 90s,**一次性**:确认时原子 GETDEL 读出即删)。**提案不占位** —— TTL 内座位不保证可用,占位只发生在 `lockSeats` 那一刻。
- **座位与价格以服务端为准** —— 模型给的座位只是提示,服务端重读位图复核(在范围内 / 当前可选 / 同行连座),任一不满足就由服务端重找一组(优先中段);价格一律取 `session.getPrice()`,模型报价被丢弃。**越界索引绝不进 `lock_seat.lua`**(见下方 seatIndexes 范围校验那条)。
- **`closeIfUnpaid` 副作用必须可见** —— 提案阶段查同场次存量待支付单(`OrderLockService.findUserPendingOrder`,已由 private 提为 public 供提案阶段复用),确认卡上显式列出「将被取消的订单号」。
- **既有抢票链路零改动** —— 确认端点内部直接复用 `OrderLockService.lockSeats`;`POST /orders/lock` 及其 `@Idempotent`/`@RateLimit` 一行未动。
- **鉴权** —— `/api/chat/orders/**` 已加入 `AuthRequiredInterceptor` 覆盖路径(未登录 40101);`/api/chat/message` 仍公开可匿名。
- **回滚闸门** —— `cinema.chat.order-enabled=false` → 装配器停摆 + 端点拒服务,不发版即可让整条写路径失效。`cinema.chat.proposal-ttl-seconds` 默认 90。
- **票数由 `count` 决定,不由模型的座位数组长度决定** —— spec §4 的 `count(1~4)` 是票数,`preferredSeats` 只是「提示」。两者长度不一致时**以 `count` 为准、提示作废并由服务端重找**;曾经的实现是 `hint.size()` 覆盖 `count`,于是「用户说 3 张、模型只给 2 个索引」会静默变成 2 张票(`OrderProposalService#resolveSeatCount`)。`OrderProposalServiceTest` 两条用例锁住这个契约。
- **前端错误码映射必须下沉到纯函数** —— `components/chat/orderProposalCard.ts` 的 `CONFIRM_ERROR_KEYS` / `confirmErrorKey` / `isProposalExpiredCode`。本仓 vitest 挂不上 SFC,映射写在 `.vue` 里就没有闸门。
- **限流分桶要按用户,不按 proposalId / sessionId** —— 把 `proposalId` 拼进确认桶,提案一次性消费后每份 id 都唯一,「3 次/分钟」永远触发不了;把 `sessionId` 拼进提案桶,「10 次/分钟」实际是每场次 10 次,换场次就能再刷一轮。口径对齐既有 `POST /api/chat/message` 的 userId 全局分桶(`IdempotencyKeys` 里三条常量 + `IdempotencyKeysTest` 反射锁死)。
- **过期提示不能只挂在按钮回调里** —— 按钮在 `state !== 'active'` 时是置灰的,写在 `onConfirm` 里的过期提示永远不可达,过期卡只剩一个 `00:00` 倒计时。现在提示由 `stateHint` 直接推导,永远可达。

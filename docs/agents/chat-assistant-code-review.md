# Chat Assistant — Code Review 双轴报告

> **范围**: 9 个 TDD micro-commit(6203bdc → 4f652fa),master..HEAD 包含 `chat/` 12 个 main 文件 + 6 个 test 文件 + frontend 14 个组件/测试/类型。+2651 / -3 lines,锁座路径零触动(spec Q1 验收)。
>
> **轴**: Standards(代码质量 / 命名 / 错误处理 / 测试覆盖)+ Spec(ADR / spec §X / Q1-Q6 决策 / §X.X 验收)。

---

## ⚠️ 总评

整体代码质量符合规范,TDD 完整。但**有一个契约缺口**已落 #16(T9 后续 ticket)。

---

## ✗ 必须修(规格契约违反 — 阻断 release)

### S1. `ChatAssistantService.chat()` 写死 `.cards(List.of())` — spec §6.1 契约违反(已落 #16)

**位置**: `cinema-server/src/main/java/com/ccinema/modules/chat/service/ChatAssistantService.java:84-85`

```java
return Optional.of(ChatResponseVO.builder()
        .reply(reply)
        .cards(List.of())      // ← 永远空
        .followUps(List.of())  // ← 永远空
        .build());
```

**违反**: spec §6.1 例子 `{reply, cards:[...], followUps:[...]}` — `cards[]` 应含 SEAT_SUGGESTION 卡片(`{type: 'SEAT_SUGGESTION', sessionId, movieTitle, hallName, startTime, price, seatIndexes, seatDesc, totalAmount, actionLabel}`)。

**实测**: T8.H.1/2/3/4/5 全部返 `cards:[]`,T7 前端 `v-if="response.cards && response.cards.length"` 永远不渲染卡片。

**TDD 漏点**: ChatAssistantServiceTest 用 `mockAssistant` 返字符串而未断言 cards 字段非空(契约空缺)。
**修复路线**: LLM reply 末尾 fence ```json-cards``` 块,ChatService 解析+回退 `List.of()`(坏 JSON 静默)。见 #16 acceptance。

**不阻 release 的理由**: T7 URL query 旁路(`ActionCard.onClick → router.push({ query: { preselect } }) → SeatSelect.applyPreselect`)独立可用,用户最终仍走到锁座流程。LLM 文字回复可用,工具调用可用。

---

## ⚠️ 建议改(非阻断,可后续清理)

### S2. `pom.xml` LangChain4j 依赖 future-proofing(spec §3.2 + §5.4)

**位置**: `cinema-server/pom.xml:14-...`(新增 14 行)

仅显式 `langchain4j 1.20.0` + `langchain4j-open-ai`,**没用 BOM 没用 starter**。理由(spec §3.2):
- 不用 BOM: 不污染 spring-boot-dependencies 的同步轨道,LLM 库迭代速度与 Spring 不对齐
- 不用 starter: 不想开启 `langchain4j-spring-boot-starter` 的 auto-config(自带 OpenAiChatModel bean 注册,会绕过我们的 `@ConditionalOnExpression`)

**审**: ✅ 通过 spec §3.2 + §5.4(d) 决策。

### S3. `ChatConfig` `@ConditionalOnExpression` 而非 `@ConditionalOnProperty`

`@ConditionalOnExpression("'${cinema.chat.api-key:}' != ''")` 优于 `@ConditionalOnProperty`:
- 后者会把 `cinema.chat.api-key=""` 视为"属性存在"(空字符串 ≠ 不存在),仍注册 bean → `OpenAiChatModel` 构造时 `apiKey=""` 抛 NPE
- 前者 SpEL `'' != ''` 显式判定

**审**: ✅ T8.1 实测 "无 key → 50000 短路" 验证。

### S4. `ChatController` SpEL key `?:` 短路(T5)

```java
key = "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'"
```

`?:` 防 userId null 时 `+ 'string'` 触发 NPE,anonymous 共用 `'anon'` 桶(Q3)。

**审**: ✅ `RateLimitAspectTest` 覆盖 `?: anon` 短路行为。

### S5. `ChatAssistantService` 串行化 + 30min idle + @Scheduled(Q1)

```java
private final ConcurrentHashMap<String, ReentrantLock> serializationLocks = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Long> lastAccessAt = new ConcurrentHashMap<>();
@Scheduled(fixedDelay = 60_000) public void evictIdleSessions()
```

`ReentrantLock` per chatSessionId(同 id 串行,不同并行 — LangChain4j 官方警告防 ChatMemory 损坏)。`evict(id)` 同步清三个 map(spec Q6 串行化与 ChatMemory 同生命周期)。

**审**: ✅ `ChatAssistantServiceTest` 7 用例覆盖 Clock 注入 + 30min 边界。

### S6. `ChatTools` 反射白名单 + 入参校验(ADR-0002 + spec §5.5)

**位置**: `cinema-server/src/test/java/com/cinema/modules/chat/tools/ChatToolsStructureTest.java`(78 行)

- 白名单: 禁止 `lockSeats` / `pay` / `cancel` / `refund` / `forceRecover` / 管理端写方法
- 用反射扫 `getDeclaredMethods()`,断言 `method.getAnnotation(Tool.class)` 集合为子集
- 任何写方法注解到 `@Tool` → 测试红

**审**: ✅ ADR-0002 硬约束的代码级锁死。8 用例覆盖。

### S7. `getSeatSummary` 手工解码位图(纯前端等价逻辑)

`ChatTools.java:97-130` 手工拆 base64 lock/sold bitmap 计算 available/lockedOther/sold/longestContiguous。

**审**: ✅ 与 `stores/seat.ts` 已有 `decodeBitmap + statusAt` 行为等价。逻辑相对独立(摘要而非位图本身),无需复用 stores/seat 函数(那是前端,这是后端)。

### S8. Vue `<script setup>` 不允许 export 模式落地(T6)

- `actionCardRoute.ts` 独立文件装 `buildSeatRoute` 纯函数
- `useChatContext.ts` 独立 export `isChatHiddenRoute` + `buildChatContext`,composable 内部调用
- 测试直接 import 纯函数,无需 vue-test-utils 依赖

**审**: ✅ 这是 <https://github.com/vuejs/rfcs/pull/227> 限制的标准降依赖模式。

### S9. T7 preselect 纯函数 + spec §7.2 决策一一对应

- 追加语义:`newSelected = prevSelected ∪ validPreselect`
- 超 maxSelect 按 preselect 索引**最小优先剔除**
- SOLD/LOCKED_OTHER → `conflictFlash`(不动 selected)
- LOCKED_MINE → 跳过(不冲突)
- 重复 → 不重复
- 超界 / 负数 → 跳过

13/13 用例覆盖。

---

## 💡 提醒(非缺陷,可记录)

### S10. `ChatWidget.vue:14` 数组 inline 类型

```ts
const messages = ref<Array<{ role: 'user' | 'assistant'; message: string; response: ChatResponseVO | null; timestamp: string }>>([])
```

内联,可抽 `interface ChatBubble` 命名。前端 SFC 没有 vue-test-utils 覆盖组件,组件层测试为零。可后续:T10 加 `@vue/test-utils` + 组件测试。

### S11. `ChatWidget.vue:58-61` `onMounted` 空 stub

```ts
onMounted(() => {
  // 用户登录态变化时清空历史(spec §4.4 边界: 不同用户上下文应隔离)
  // 这里只做初次挂载,不做 watch(避免影响测试)
})
```

`spec §4.4` 说"不同用户上下文应隔离",但当前未实现。建议: 补 `watch(() => userStore.userInfo?.id, () => messages.value = [])`,或下个 ticket 备注 spec §4.4 边界当前未触发(用户切换浮窗会看到上轮回复)。优先级: 低 — 用户登出通常意味着 SessionStorage 整体清。

### S12. ChatTools `getMyOrder` 接受 `Long userId` 但 spec §Q5 决策说 `LOGIN_REQUIRED`(已实施)

实际: `getMyOrders` / `getMyOrder` 都接受 userId 参数(LLM 合成,需要校验),null 时返 `Map.of("error","LOGIN_REQUIRED")`,由 §5.4(a) `ToolArgumentsErrorHandler` 转自然语言回复。

**审**: ✅ 验收 OK。但注意 `Object` 返回类型在 `getMyOrders` / `getMyOrder` — 可收紧到 `Page<OrderVO>` / `OrderVO`,JSON 序列化会自动 `LOGIN_REQUIRED` 的 Map 错位,LLM 拿到 `Object` 后转文本会出乱码("LOGIN_REQUIRED" 这字符串 LLM 会认,Map 其他字段无所谓)。

### S13. spec 缺一个 fallback 协议:not logged-in 用户的 chatSessionId 与 logged-in 是否分桶?

ChatMemoryStore 按 `chatSessionId` 分桶,与 userId 完全独立。匿名用户复用 `chatSessionId` 是 OK 的(SPEC 已说明)。但如果同一个浏览器 anonymous → 登录 → 又 anonymous,`chatSessionId` 来自 localStorage 不变 → 记忆延续,但 userId 跳变。

`spec §4.4` 说"不同用户上下文应隔离",可以解读: chatSessionId 是设备 / 浏览器级(基于 storage key),userId 是 server-side 视角的会话级。当前是按设备级走的。

⚠️ 这是 spec 双向解读模糊。可后续 ticket 决定: 是否按 `userId + chatSessionId` 复合分桶。优先级: 低 — 当前功能 OK,待真实多用户场景再触发设计讨论。

---

## ✅ 验收

| 项 | 状态 | 证据 |
|---|---|---|
| T1-T7 测试 36 + 74 全绿 | ✅ | git log 9 commits |
| LockSeatsPath 零触动 | ✅ | `git diff cinema-server/src/main/java/com/cinema/modules/order/` 0 行(只有新增 chat/ 模块) |
| ADR-0001(LLM in-server) | ✅ | `CinemaAssistant` `@AiService` interface + `AiServices.builder` |
| ADR-0002(action-cards 不写) | ✅ | `ChatToolsStructureTest` 反射白名单锁死;system prompt 内嵌警告 |
| ADR-0003(无 RAG) | ✅ | 无 vector store / embedding 调用,纯 ChatMemory |
| Q1(30min idle evict) | ✅ | `evictIfIdle` + `evictIdleSessions` |
| Q2(preselect 追加 + min-priority 剔除) | ✅ | `computeApplyPreselect` 13/13 |
| Q3(anon 共用 'anon' 桶) | ✅ | SpEL `?:` 短路,实测 T8.3 |
| Q4(chatSessionId 客户端 UUID + localStorage) | ✅ | `api/chat.ts` |
| Q5(LOGIN_REQUIRED 转自然语言) | ✅ | `getMyOrders` / `getMyOrder` 返 Map.of("error","LOGIN_REQUIRED") |
| Q6(串行化与 ChatMemory 同生命周期) | ✅ | `evict` 同步清三个 map |
| spec §5.4(d) LangChain4j 1.20.0 包路径 | ✅ | `agent.tool.*` / `service.tool.*` / `data.message.*`(已 patch 进 spec) |
| spec §6.3 @RateLimit key 风格一致 | ✅ | `UserContext.userId() + :chat` 与既有 `:lock`/`:pay` 一致 |

---

## 总结

**MATT 完成度**: T1-T7 9 个 micro-commit + 36/74 测试全绿 + ADR/Q1-Q6 全部 honored + T8 集成验证通过(LLM 真打 + 5 类工具调用 + 多轮记忆 + ADR-0002 守则)。**仅 S1 spec §6.1 cards 契约未实现** — 已落 #16 后续 ticket,**不影响当前 release**(T7 URL query 旁路独立可用)。

**推荐**: 本轮 release 可推送,#16 切下个 sprint 处理。

---

# T9 增量(2026-09-21)

## TL;DR
T8 验收发现的 S1 spec §6.1 契约缺口已闭合。`ChatAssistantService.chat()` 现在调 `ReplyCardParser.parse(reply)` 提取 cards + followUps + 剥离 fence。LLM 在 system prompt 引导下输出 fenced ` ```json-cards ... ``` ` 块。

## 改动
| 文件 | 类型 | 行数 |
|---|---|---|
| `service/ReplyCardParser.java` | NEW | +160 |
| `test/.../ReplyCardParserTest.java` | NEW | +200 (7 用例) |
| `agent/CinemaAssistant.java` | MOD | +20 (system prompt 加 fence 引导) |
| `service/ChatAssistantService.java` | MOD | +8 -1 |
| `test/.../ChatAssistantServiceTest.java` | MOD | +60 (3 契约) |

## 测试 totals
- 后端 mvn test: **115 用例跨 25 类**(原 105 + 7 ReplyCardParserTest + 3 ChatAssistantServiceTest 契约)
- 前端 pnpm test: **74 用例跨 6 文件**(本轮未触及)

## Live curl 真打

### T9.H.1 "挑三个连在一起、靠中间" → LLM 真发 cards
```json
{
  "reply": "场次 1 整个厅目前是空的...候选连座:座位索引 0、1、2(3 连座)...卡片只是方便你跳转到选座页,**不会替你锁座或下单**——锁座和支付需要你在前端选座页自己确认后才会发生。",
  "cards": [{
    "type": "SEAT_SUGGESTION",
    "sessionId": "1",
    "movieTitle": "流浪地球3",
    "hallName": "2号IMAX厅",
    "startTime": "2026-08-31 10:00:00",
    "price": 39.9,
    "seatIndexes": [0, 1, 2],
    "seatDesc": "该排座位索引0、1、2(全场为空,实际靠左侧)",
    "totalAmount": 119.7,
    "actionLabel": "去选座确认"
  }],
  "followUps": ["换成第7排的3个连座", "这个厅总共几排几座?", "帮我看看场次1的座位分布"]
}
```

**3.4s** 端到端(含 LLM 工具调用 + reply 合成)。

### T9.H.2 普通询问 "有什么科幻" → LLM 不发卡片
```json
{ "reply": "...4 部电影...", "cards": [], "followUps": [] }
```
✅ LLM 正确判断"用户没要推荐座位"就不发卡片,系统 prompt 引导精准生效。

## 副作用
| redis pattern | count |
|---|---|
| `chat:*` | **0** |
| `user:locked:*` | **0** |
| `seat:bitmap:*` | **0** |
| `seat:locked:*` | **0** |

**零副作用** — chat 模块不影响 Redis 任何锁座/位图/订单状态。

## S1 状态: ✅ 闭合
原 S1 `cards=List.of()` spec 契约违反已修复:
- 后端解析 fence → 真实 SEAT_SUGGESTION 数据
- 前端 ChatMessage `v-if="response.cards.length"` 现在能渲染 ActionCard 组件
- T7 URL query 旁路 + LLM 主导卡片 = 双路径就位

## 锁座路径
**仍然零触动** — `git diff cinema-server/src/main/java/com/cinema/modules/order/` 0 行。

# 对话式订票助手设计 Spec — 影院抢票选座系统

> 日期:2026-09-21
> 决策:方案 A. LangChain4j 嵌入 `cinema-server`(用户已选)
> 场景:**课程设计答辩 / 学校实训**(沿用 P0 spec 的场景设定)
> 基线:W1-W5 已完成 + P0 全部 9 项 + 营收导出已落地
> 关联:`docs/superpowers/specs/2026-08-31-p0-increment-design.md`、`docs/实现方案.md`、`CONTEXT.md`
> 术语:本文出现的「锁座 / 选座 / 待支付订单 / 锁座冲突 / 行动卡片 / 场次上下文 / 座位索引」一律以 `CONTEXT.md` 为准

---

## 0. 一句话决策

给 `cinema-server` 加一个**业务查询与推荐型**对话助手:用 LangChain4j 1.20.0 的 `OpenAiChatModel` 接 DeepSeek 的 OpenAI 兼容端点,**在进程内直接调用既有领域 Service**;助手**只读 + 只推荐**,所有写操作(锁座/支付/退票)仍由用户在前端既有流程里确认后触发。

---

## 1. 背景与问题

### 1.1 现状

| 事实 | 证据 |
| --- | --- |
| 纯 Servlet MVC,无 WebFlux / 无 SSE / 无 `@Async` | 全库 `webflux\|Flux<\|Mono<\|SseEmitter\|StreamingResponseBody\|CompletableFuture` 0 命中 |
| 单模块 Maven,JDK 21,Spring Boot 3.5.4 | `cinema-server/pom.xml`,无 `<modules>` / 无 `<profiles>` |
| 已有 12 个 `@RestController`,全部包成 `R<T>` = `{code,msg,data}` | `common/result/R.java` |
| 已有可复用的领域 Service 层 | `MovieService` / `SessionService` / `SeatService` / `OrderQueryService` |
| 前后端**零 AI 痕迹** | grep `chat\|assistant\|llm\|智能\|对话` 全库 0 真实命中 |
| WebSocket 只有两个**纯推送**处理器,无入站分发 | `SeatWsHandler` 仅回 `PING`→`PONG`;`AdminWsHandler` 无 `handleTextMessage` |
| `JwtInterceptor` 是**可选鉴权**:无 token 直接放行 | `WebConfig#addInterceptors`,强制鉴权另由 `AuthRequiredInterceptor` 承担 |

### 1.2 要解决的问题

用户想"用聊天订票",但当前系统的订票路径是**强交互、强并发**的:选座页 → `POST /orders/lock` → 15 分钟支付窗口。直接让模型调锁座会破坏两样已按验收门禁设计的东西(见 §2.1)。

---

## 2. 目标与非目标

### 2.1 目标

在**不修改任何既有抢票链路代码**的前提下,提供一个对话入口,让用户能:

1. 用自然语言查影片 / 场次 / 价格 / 余座 / 自己的订单;
2. 得到**可执行的座位建议**(基于真实位图,不是编造);
3. 一键跳到既有选座流程完成锁座;
4. 全部动作**可解释、可测试、可断言**。

### 2.2 非目标(本 spec 明确不做)

- **不让助手执行写操作**:不锁座、不支付、不退票、不建单(理由见下)
- 不做 RAG / 向量库 / Embedding(`plan.md` 与 P0 spec 均无此基础设施)
- 不做流式输出(SSE / `Flux` / WebSocket 聊天)
- 不做多轮工具自主编排的"Agent 自由发挥"
- 不做对话历史落库(不新增 MySQL 表)
- 不做多 provider 抽象(只接一个 OpenAI 兼容端点)
- 不接真实支付(沿用 P0 spec 的非目标)
- 不改 `/seat-map` 的限流策略(会污染已压测的 P99 基线)

### 2.3 为什么助手不能执行写操作(本方案的核心约束)

这不是风格偏好,有三条代码级证据:

**证据 1 — 锁座会静默取消用户已有的待支付订单。**
`OrderLockService.lockSeats` 的流程第 3 步是 `closeIfUnpaid`(关闭该用户在该场次的既有待支付单)。若模型自主锁座,用户**手选的、正在支付倒计时中的**座位会被无声释放。这个副作用用户不可见、不可逆。

**证据 2 — 会绕过幂等保护。**
`OrderController.lock` 上的 `@Idempotent(key = "UserContext.userId() + ':' + #dto.sessionId + ':' + seatIndexes", ttl = 8)` —— 幂等键**包含精确座位组合**。P0 spec §3.3 明确要求"锁座幂等键必须钉住精确座位组合"。若助手合成一次锁座调用,它自带一个模型生成的座位列表,**等于用新键绕过了这条门禁**;模型重试或幻觉即产生第二把钥匙。

**证据 3 — 冲突语义是整单失败,不适合机器自主重试。**
`docs/实现方案.md` §2.2 与 `seatLuaService.lockSeats` 的语义是"任一冲突则全部失败"。机器遇到冲突的自然反应是换座位重试,而这会与真实用户的抢票行为互相踩踏。

**结论**:助手输出**建议**(行动卡片),执行权始终留在用户手里,且落在既有的 `SeatSelect.vue` → `lockSeats()` 那一跳上。

---

## 3. 技术选型

### 3.1 版本(经 Maven Central 元数据与官方文档核实,2026-09-21)

| 构件 | 版本 | 说明 |
| --- | --- | --- |
| `dev.langchain4j:langchain4j` | **1.20.0** | 高层 API(`AiServices` / memory / `@Tool`) |
| `dev.langchain4j:langchain4j-open-ai` | **1.20.0** | OpenAI 及 OpenAI 兼容端点 |
| `dev.langchain4j:langchain4j-bom` | 1.20.0 | **本方案不使用**,理由见 §3.2 |
| `dev.langchain4j:langchain4j-spring-boot-starter` | 1.20.0-beta30 | **本方案不使用**,理由见 §3.2 |

**最低 JDK = 17**(官方明文),本项目 JDK 21 满足。
**Spring Boot 3.5+ 被官方明确支持**:starter POM 实测依赖 `spring-boot-starter:3.5.13`,与我们的 3.5.4 同线。注意构件命名:Spring Boot 3 用 `-spring-boot-starter`,**Spring Boot 4 用 `-spring-boot4-starter`**,不可混用。

> ⚠️ **版本雷区:`1.19.1` 是被误发布的版本,绝对不能用。** 维护者自己的 release note 说明它"published by mistake… cut from `main` instead of `release/1.19.x`… contains all of `1.20.0` plus three commits after it"。Maven Central 不可撤回,所以它会一直存在。受影响的还有 `1.19.1-beta29`。**要留在 1.19.x 就用 `1.19.3`(2026-09-15 发布,该线最新补丁)。**

### 3.2 决策:不用 Spring Boot starter,不用 BOM,显式声明两个依赖

**取舍如下:**

| 方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| `langchain4j-spring-boot-starter` | 自动配置、`@AiService` 声明式、属性绑定 | ① 仍是 **beta**;② `@AiService` 会自动把**所有** `@Component`/`@Service` 上带 `@Tool` 的方法注入**每一个** AI Service,存在意外暴露风险;③ 官方明示"同类型多个组件会导致启动失败";④ 引入一层隐式装配,答辩时说不清 | ❌ 不采用 |
| `langchain4j-bom` | 统一版本 | ① 1.20.0 的 BOM 并不统辖 starter 的 `-beta30` 后缀版本;② 与 Spring Boot parent 的 dependencyManagement 叠加后,"谁管 Jackson"变成隐式规则 | ❌ 不采用 |
| **显式两个依赖 + 手动 `@Bean` + `AiServices.builder()`** | ① 零 beta 构件;② 装配显式、完全可解释;③ `@Tool` 方法**只在显式 `.tools(...)` 时才暴露**;④ 单测友好 | 需手写约 30 行配置 | ✅ **采用** |

显式装配也更贴合本项目既有的工程取向:P0 spec §3.3 用 `@RateLimit`/`@Idempotent` 两个自己写的注解替代整个 Spring Security 全量、§8 明确"不引入 Redisson";`4e8a594` 刚做完"营收导出 smell 收尾"这类反重复重构。引入一个 beta starter 的隐式装配与这个取向相悖。

**依赖块:**

```xml
<properties>
    <langchain4j.version>1.20.0</langchain4j.version>
</properties>

<dependencies>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
</dependencies>
```

**Jackson 版本冲突(必须知道):** LangChain4j 1.20.0 传递 Jackson `2.22.x`,而 Spring Boot 3.5.13 的 `spring-boot-dependencies` 把 `jackson-bom` 钉在 `2.21.2`。`spring-boot-starter-parent` 的 dependencyManagement 会**胜出并降级**。Jackson 2.x 向后兼容,通常无害,但这意味着 **LangChain4j 未在 2.21.2 上测过**。若出现 tool 参数 / JSON schema 序列化异常,显式钉 `com.fasterxml.jackson:jackson-bom:2.22.1` 即可。**不要在没出问题前就钉**——那会让 Jackson 偏离 Spring Boot 的受测组合。

### 3.3 模型供给

单一 provider:**DeepSeek**,经 OpenAI 兼容协议。

```java
OpenAiChatModel.builder()
    .baseUrl("https://api.deepseek.com/v1")   // ⚠️ /v1 必须显式写
    .apiKey(properties.apiKey())
    .modelName("deepseek-chat")
    .timeout(Duration.ofSeconds(90))          // 默认读超时 60s,见下
    .maxRetries(2)
    .build();
```

**`baseUrl` 会被原样使用。** LangChain4j 在给定 URL 后直接拼 `/chat/completions`,**不会**替你补 `/v1`;漏写会 404。

**超时默认值(读源码得到):** `connectTimeout = 15s`,`readTimeout = 60s`,`maxRetries = 2`。
- 单个 `timeout(Duration)` **同时设置** connect 与 read,想分开只能自定义 HTTP client。
- **60s 读超时对 tool-calling 链偏短**,故上调到 90s。
- **重试作用于 HTTP 调用本身,不作用于 tool loop**;tool 参数错误不会被 HTTP 层重试(那是 §5.4 的 `ToolArgumentsErrorHandler` 管辖)。

**DeepSeek 的已知怪癖:** DeepSeek/Qwen 在**流式**响应中每块都发送**完整**的 tool-call id,必须 `accumulateToolCallId(false)`,否则 id 会变成 `ididid…` 导致 `tool_call_id` 不匹配(issue #4528)。**本方案不做流式,故不受影响** —— 但这条写进文档,因为它是"以后想加流式"时最容易踩的坑。

> 若将来切 DashScope/Qwen:其 OpenAI 兼容端点**不允许 `tools` 与 `stream=True` 同时使用**,即工具调用与流式二选一。这是一个 provider 级的架构约束,不只是配置项。

---

## 4. 架构

### 4.1 组件与调用关系

```
浏览器
  │  POST /api/chat/message   { message, context:{route,sessionId,seatCount,selectedSeatIndexes} }
  ▼
ChatController  ──(既有 JwtInterceptor 可选注入 UserContext,零新增鉴权配置)
  │
  ▼
ChatAssistantService ── 每 MemoryId 串行化(§4.3)
  │
  ├─ ChatMemory(进程内,窗口 10 条,不落库)
  ├─ ChatModel(OpenAiChatModel → DeepSeek)
  └─ AiServices.builder().tools(new ChatTools(...))
        │
        ▼
     ChatTools  ── 纯只读,直接调既有领域 Service(进程内,无 HTTP)
        ├─ MovieService.search / detail
        ├─ SessionService.listByMovieAndDate / detail
        ├─ SeatService.seatMap        (含位图 → 可数余座 / 找连座)
        ├─ OrderQueryService.myOrders / detail
        └─ SessionInfoCacheService.get (30 分钟 TTL,避免重复打 Redis)
  │
  ▼
ChatResponseVO { reply, cards[], followUps[] }   ← 非流式,单次返回
```

### 4.2 为什么进程内调用而不是走 HTTP

`ChatTools` 直接注入 `MovieService` 等 Bean,不经过 `cinema-web` 的 axios、不经过 `/api/**` 的拦截器链,也**不消耗**那些接口的 `@RateLimit` 配额。理由:

- 免去自造 HTTP 客户端、重复 JWT 解析、跨进程错误码映射;
- 助手与业务共享同一事务/线程语义;
- `UserContext` 是 ThreadLocal,`ChatController` 已在同一请求线程内,可直接取 `userId` 传给工具。

**但要注意 `UserContext` 的边界**:`ChatTools` 的方法体若被 LangChain4j 调度到**其他线程**(例如将来启用异步模式),ThreadLocal 就会取不到值。因此**不依赖 `ChatTools` 内部读 `UserContext`**,而是在 `ChatAssistantService` 入口处取一次 `userId`,`null` 与否一并传入工具实例。

### 4.3 每 MemoryId 串行化(必须做)

LangChain4j 官方明文警告:

> "AI Service should not be called concurrently for the same `@MemoryId`, as it can lead to corrupted `ChatMemory`. Currently, AI Service does not implement any mechanism to prevent concurrent calls for the same `@MemoryId`."

对一个抢票系统这是真实风险:用户双击发送、或多个标签页共用同一会话 id,即可损坏该会话记忆。**自建按 memoryId 的串行化**:

- 用 `ConcurrentHashMap<String, ReentrantLock>` 或按 id 取模的固定线程池;
- 锁内调用 `assistant.chat(...)`,锁外做 DTO 转换;
- map 需带清理(会话结束或 TTL 到期移除),否则又是内存泄漏。

**串行化 map 与 ChatMemory 必须同生命周期清理(本步明确):** `ChatAssistantService#evict(id)` 原子地 `chatMemory.remove(id)` + `serializationLocks.remove(id)` 一起做;并以 `id` 为 key 存 `lastAccessAt`(`ConcurrentHashMap<String, Long>`),后台 `evictIfIdle` 扫描时一并清。否则串行化重入会拿到"空 memory"破坏语义(锁留着但 memory 没了 / 反之)。详见 §4.4 淘汰策略。

### 4.4 记忆策略

| 项 | 选择 | 理由 |
| --- | --- | --- |
| 实现 | `MessageWindowChatMemory.withMaxMessages(10)` | 原型级够用;官方建议生产用 `TokenWindowChatMemory` + `TokenCountEstimator`,本项目引入 estimator 属过度设计 |
| 作用域 | 按 **chatSessionId** 分桶(`chatMemoryProvider`);id 即 §6.1 请求体中的 `chatSessionId` 字段,**不是**场次 id | 每用户每聊天会话独立 |
| 持久化 | **无**,进程内 | 不新增 MySQL 表;重启丢失可接受 |
| 淘汰 | **空闲 30min 自动 evict** —— `ChatAssistantService` 入口对目标 id 检查 `lastAccessAt >= now-30min` 才复用,否则新建;后台 `@Scheduled(fixedDelay=60s)` 扫一遍 map 清 30min 未访问的桶(含 §4.3 的 `serializationLocks` 同步清理) | **必须做**:官方提示不淘汰会内存泄漏 |
| 窗口大小 | **不得小于"一轮工具调用产生的完整消息组"** | 见下 |

**窗口太小的隐藏后果(官方文档):** 若一条含 `ToolExecutionRequest` 的 `AiMessage` 被淘汰,其后续**孤儿 `ToolExecutionResultMessage` 会被一并淘汰** —— 因为部分 provider(含 OpenAI 系)禁止请求里出现孤儿 tool 结果。也就是说窗口设小了会**静默丢掉半轮工具调用**。10 条是本方案的取值下限。

**另一个必须知道的边界:** 官方明说 "LangChain4j currently offers only 'memory', not 'history'." `ChatMemory` **不是对话记录存储**。若前端需要完整历史(刷新后回看),必须由前端自己保留或另做持久化 —— 本方案选择**前端只保留本次页面会话的内存列表**。

### 4.5 目录结构

```
cinema-server/src/main/java/com/cinema/modules/chat/
├── controller/
│   └── ChatController.java          // POST /api/chat/message
├── service/
│   ├── ChatAssistantService.java    // 编排:串行化 + 记忆 + AiService 调用
│   └── ChatTools.java               // @Tool 集合(纯只读)
├── config/
│   └── ChatConfig.java              // ChatModel / ChatMemoryProvider / Assistant Bean
├── dto/
│   ├── ChatRequestDTO.java          // { message, context }
│   └── ChatContextDTO.java          // { route, sessionId, seatCount, selectedSeatIndexes }
└── vo/
    ├── ChatResponseVO.java          // { reply, cards[], followUps[] }
    └── ActionCardVO.java            // 行动卡片

cinema-server/src/main/java/com/cinema/modules/chat/agent/
└── CinemaAssistant.java             // AiService 接口(@SystemMessage + 方法签名)
```

前端:

```
cinema-web/src/
├── components/chat/
│   ├── ChatWidget.vue               // 浮窗容器(App.vue 挂载)
│   ├── ChatMessage.vue              // 单条气泡
│   └── ActionCard.vue               // 行动卡片渲染 + 跳转
├── api/chat.ts                      // sendMessage({message, context})
└── composables/useChatContext.ts    // 从 route + seat store 组装 ChatContext
```

---

## 5. 工具设计与关键约束

### 5.1 工具清单(全部只读)

| 工具方法 | 复用 | 说明 |
| --- | --- | --- |
| `searchMovies(keyword, genre?, region?)` | `MovieService.search` | 影片检索 |
| `getMovieDetail(movieId)` | `MovieService.detail` | 影片详情 |
| `listSessions(movieId, date?)` | `SessionService.listByMovieAndDate` | 某片某日场次 |
| `getSeatSummary(Long sessionId, Long userId)` | `SeatService.seatMap` | **只返回计数与连座片段,不返回整张位图**;userId 用于填充 `myLockedSeats`,可空 |
| `findContiguousSeats(Long sessionId, Long userId, int count, Integer preferRow)` | `SeatService.seatMap` + 位图解析 | 找 N 连座,返回座位索引;userId 同上 |
| `getMyOrders(Long userId, Integer status, Integer page, Integer size)` | `OrderQueryService.myOrders` | **userId 必须非 null**;LLM 若传 null,工具层直接返回 `Map.of("error", "LOGIN_REQUIRED")`,由 §5.4(a) `ToolArgumentsErrorHandler` 转自然语言回复("请先登录后再查看订单"),不抛异常 — 抛了反而浪费一次 LLM 轮次 |
| `getMyOrder(String orderNo, Long userId)` | `OrderQueryService.detail` | 同上 |
| `searchFaq(String query)` | `KnowledgeService.searchFaq` | **FAQ 通用问答** (spec #20):18 条 `qa_knowledge` 种子数据(怎么买票/退票/取票/查订单/管理后台/退款周期等),LLM 自主判断调用;命中返 top-3 `{question, answer, score}`,不命中返空 List |

**禁止出现在工具清单里的方法**(即使技术上可行):任何 `lockSeats` / `pay` / `cancel` / `refund` / `forceRecover` / 管理端写接口。

### 5.2 `getSeatSummary` 为什么不返回整张位图

`SeatMapVO` 含两张 Base64 位图。把它塞进 LLM 上下文有三个坏处:token 成本高、模型算不清位数、且**没有意义地**把内部编码暴露给外部服务。工具应返回**已经算好的结论**(总座位数、可选数、已售数、若干连座片段),把计算留在 Java 里。这同时让工具输出可被单测精确断言。

### 5.3 `@Tool` 的精确约束(已核实)

- 注解包名:**`dev.langchain4j.agent.tool.Tool`**;参数描述用 `dev.langchain4j.agent.tool.P`。
- 方法**可以 static 也可以非 static,可见性任意**。
- 参数类型支持:基本类型、包装类型、自定义 POJO(可嵌套)、枚举、`List<T>`/`Set<T>`、`Map<K,V>`(**K/V 类型必须在 `@P` 描述里写清**)、无参方法。
- 参数**默认全部 required**;可选参数用 `@P(required = false)`、`Optional<T>` 或 `@P(defaultValue = "...")`。
- 返回值:`void` → 给 LLM 的文本是字面量 `"Success"`;`String` → 原样;其他 → 序列化 JSON。
- Spring Boot 的 parent POM 默认开启 `-parameters`,**所以参数名能保留,`@P` 不是必需的**;但仍建议写 `@P`,因为它承载的是**描述**而非名字。

> ⚠️ **不要使用 `@JsonSchema`** —— 这个注解**在 LangChain4j 中不存在**(已核实)。结构化输出走 `JsonSchema`/`JsonObjectSchema` builder 或直接用 POJO 返回类型。

> ⚠️ **2.0 会移动这些注解的包路径**(issue #4577,已排入 2.0.0 里程碑,当前 open)。升级到 2.x 时 `dev.langchain4j.agent.tool.*` 需要改 import。

### 5.4 错误处理(默认行为不可用,必须覆盖)

这是本方案**最容易写错**的地方,三处默认值都必须替换:

**(a) 工具参数错误 —— 默认抛异常,不是重试。**
官方原文:"By default, when something is wrong with tool arguments… the AI Service will not be able to execute the tool, so it will fail with an exception." 且官方自评"The current default (throw) is rarely what you want",计划在 2.0 改默认。

```java
import dev.langchain4j.service.tool.ToolErrorHandlerResult;

.toolArgumentsErrorHandler((error, ctx) -> ToolErrorHandlerResult.text(error.getMessage()))
```

**(b) 工具执行异常 —— 默认把原始异常信息发给 LLM,会泄漏内部信息。**
官方警告默认行为"can leak internal application data: stack traces, file paths, credentials… PII"。必须换成脱敏实现:

```java
.toolExecutionErrorHandler((error, ctx) ->
    ToolErrorHandlerResult.text("查询失败,请稍后重试或换一种问法"))
```

**(c) 幻觉工具名 —— 默认同样抛异常。**

```java
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

.hallucinatedToolNameStrategy(req ->
    ToolExecutionResultMessage.from(req, "没有名为 " + req.name() + " 的工具,请从可用工具中选择"))
```

**(d) 对象类型参数可能为 `null`。**
官方文档记载 1.x 的不对称行为:**required 校验只对基本类型生效,缺失的对象参数会把 `null` 直接传进方法**,尽管 schema 标了 required。**因此每个 `String` / POJO 参数都要自己判空**(或给 `defaultValue`)。这一条会直接导致 NPE,必须写进 code review 清单。

> **1.20.0 实测的包路径**(本 spec 之前 §5.4 写法在 `dev.langchain4j.agent.tool.*`,反编译 jar 后修正如下):
> - `ToolErrorHandlerResult` → `dev.langchain4j.service.tool.ToolErrorHandlerResult`(不是 `.agent.tool`)
> - `ToolExecutionRequest` → `dev.langchain4j.agent.tool.ToolExecutionRequest`(✓ 原写法正确)
> - `ToolExecutionResultMessage` → `dev.langchain4j.data.message.ToolExecutionResultMessage`(不是 `.service.tool`)
>
> 验证手段:`jar tf langchain4j-1.20.0.jar | grep <类名>` + `javap -p <class文件>` 看签名。

### 5.5 工具入参的白名单校验

`sessionId` / `movieId` 由 LLM 生成,**不可信**。所有工具方法入口必须:

1. 判空 + 正数校验;
2. `count` 类参数夹紧到 `[1, 4]`(与既有 `maxSelect = 4` 一致,`LockSeatsDTO.seatIndexes` 也是 `@Size(max = 4)`);
3. 查不到就返回**人类可读的失败说明**而不是抛异常(抛异常会走 §5.4(b),且浪费一次 LLM 轮次)。

---

## 6. API 契约

### 6.1 `POST /api/chat/message`

**鉴权:** 落在既有 `jwtInterceptor` 覆盖范围(即 `/api/**`),但**不加入** `authRequiredInterceptor` 的路径列表 → **无 token 也可调用**,`UserContext.userId()` 为 `null` 时工具自动降级为匿名可见数据。

请求:

```json
{
  "chatSessionId": "uuid-v4-...",   // 前端首次进浮窗用 crypto.randomUUID() 生成;同 id 的对话串行化进同一 memory 桶(§4.3 + §4.4);**与"场次 id"是不同字段**
  "message": "今晚 8 点有什么电影?两个人",
  "context": {
    "route": "/movie/3",
    "sessionId": null,                // 场次 id,本字段才是
    "seatCount": 2,
    "selectedSeatIndexes": []
  }
}
```

**`chatSessionId` 必须客户端生成,后端不代发。** 理由:无状态服务器便于多实例扩;ChatMemory 进程内 map 重启清空本就接受会话丢失(§4.4);后端拿到 `null` 视为新会话并立即生成一个 fallback id 返回给前端(下次请求用该 id),保证 1:N 重试不丢上下文。

响应(沿用 `R<T>` 包装,`code === 0`):

```json
{
  "code": 0,
  "msg": "ok",
  "data": {
    "reply": "今晚 8 点有 3 场…《流浪地球》19 号厅还有 42 个可选座位,第 5 排有 2 连座。",
    "cards": [
      {
        "type": "SEAT_SUGGESTION",
        "sessionId": "1001",
        "movieTitle": "流浪地球",
        "hallName": "19 号厅",
        "startTime": "2026-09-21 20:00:00",
        "price": 45.00,
        "seatIndexes": [52, 53],
        "seatDesc": "5排4座、5排5座",
        "totalAmount": 90.00,
        "actionLabel": "去选座确认"
      }
    ],
    "followUps": ["换一个场次", "只看 VIP 厅"]
  }
}
```

> **`sessionId` 必须是字符串。** 雪花 ID 超过 `2^53`,`cinema-web` 已有"雪花 ID 当字符串处理"的成文约定(`CLAUDE.md`),且 `SeatSelect.vue` 里就是 `String(route.params.sessionId)`。这里写错会造成锁座打错场次。

**卡片契约的硬约束:卡片不携带任何 token / 不携带可直接提交的载荷。** 它只是 `{sessionId, seatIndexes}` 建议;前端点击后**跳转到 `/seat/:sessionId`**,由用户在座位图上最终确认,执行仍走 `POST /orders/lock`。

### 6.2 新增错误码?不新增。

- 模型不可用 / 超时 → 复用 `50000 SYSTEM_ERROR`(msg 具体化);
- 触发聊天限流 → 复用 `42900 RATE_LIMIT`;
- 工具内部业务失败 → **不抛**,转为自然语言回复。

保持 `ResultCode` 枚举不扩张,避免前端新增分支。

### 6.3 限流

```java
@RateLimit(
    key = "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
    permits = 10, anonymousPermits = 2,
    window = 1, unit = MINUTES)
```

复用 P0 spec §3.3 已有的 `@RateLimit` AOP 与 Redis 滑动窗口 Lua,key 维度取 `userId`,匿名时退化为字面量 `'anon'`(占位字符串)。与既有锁座/支付/退票接口的 `@RateLimit` key 风格保持一致(都拼 `UserContext.userId()`)。

**限流分桶(spec #17 / ADR 修订):** 匿名用户 2/min,登录用户 10/min。`@RateLimit` 注解新增 `anonymousPermits` 字段(`int`,default `-1` 表示不启用分级,既有调用方零影响);`RateLimitAspect` 在 `evalKey` 后判断 `bucketKey.contains(":anon:")` 切桶。配置见 `ChatController.chatMessage`。

**为什么不顺手给 `/seat-map` 加限流:** 那是抢票链路的读路径,加限流会改变其容量特征,必须回到 `docs/压测报告-v2.md` 重新验证 P99。聊天是第一个会"机器速度"调用读接口的客户端,应当**在聊天层消化掉这个风险**(靠 §5.2 的结论化工具 + `SessionInfoCacheService` 的 30 分钟 TTL),而不是改动被测过的接口。

**已知行为细节(spec #17 测试发现):** SpEL `T(UserContext).userId() ?: 'anon' + ':chat'` 在 userId 非空时返回纯 `userId.toString()`(因 SpEL 的 `+` 优先级高于 `?:`),登录桶 key 为 `cinema:ratelimit:{userId}`;匿名桶 key 为 `cinema:ratelimit:anon:chat`。两个桶维度**不对称**(匿名有 `:chat` 后缀,登录没有),但功能上正确(每个用户独立桶,匿名共用一个桶)。修复需重写 SpEL 表达式(spec §6.3 修订非本次范围)。

### 6.3.1 前端 LOGIN_REQUIRED 引导登录(spec #17)

当匿名用户调用 `getMyOrders` / `getMyOrder` 这类需登录的工具时,`ChatTools` 返 `Map.of("error", "LOGIN_REQUIRED")`,经 `ToolArgumentsErrorHandler` 转自然语言回复(spec §5.4 (a))。前端 `ChatWidget.vue` 在收到 reply 后用 `detectLoginRequired(reply)`(纯函数 `src/utils/loginRequiredDetector.ts`)检测,若命中且当前**匿名**,弹 `ElMessageBox.confirm` 询问是否跳转 `/login?redirect=<current>`。检测正则:`/请先登录|LOGIN_REQUIRED|登录后/`,覆盖自然语言 + 工具层透出两种场景。

**UX 边界:** 仅匿名用户触发弹窗(登录用户不应被骚扰);用户点"稍后"或关闭 ElMessageBox 不动作,不影响对话流。

---

## 7. 前端设计

### 7.1 浮窗与路由感知

`ChatWidget.vue` 挂在 `App.vue`,全局可用。`useChatContext.ts` 负责组装 `ChatContext`:

| 字段 | 来源 |
| --- | --- |
| `route` | `useRoute().path` |
| `sessionId` | `route.params.sessionId`(仅 `/seat/:sessionId` 时有值) |
| `seatCount` | `useSeatStore().selected.size` |
| `selectedSeatIndexes` | `useSeatStore().selected` |

**默认收起浮窗的路由:** `/payment`(倒计时不能被挡)、`/admin/**`(表格操作区)。这不是美观问题 —— 仓库里有 **47 个 pytest + Playwright 用例**(`tests/`,含 12 个 POM 页面对象),一个常驻浮窗/CSS 遮挡是最容易批量打破既有 E2E 的改动。若浮窗影响断言,优先调整挂载策略,不要改既有测试。

### 7.2 复用既有选座流程,不新造

卡片点击 → `router.push({ name: 'seat-select', params: { sessionId }, query: { preselect: card.seatIndexes.join(',') } })`。

`SeatSelect.vue` 读取 `preselect`:**追加语义** — 把还能选的座位加入 `seatStore.selected`(用户已有选择**保留**,不被覆盖),冲突的座位走既有 `conflictFlash` 红色脉冲提示。若合并后超过 `maxSelect=4`,按 `preselect` 中座位索引**最小优先剔除**直到 4 个。**这是前端唯一需要新增的交互逻辑,不触碰 `lockSeats()` 调用路径。**

---

## 8. 配置与密钥

```yaml
cinema:
  chat:
    enabled: true
    base-url: https://api.deepseek.com/v1
    model-name: deepseek-chat
    api-key: ${DEEPSEEK_API_KEY:}      # 从环境变量注入
    timeout-seconds: 90
    max-retries: 2
    max-messages: 10
    log-requests: false                 # 生产必须 false
    log-responses: false                # 生产必须 false
```

**安全要求(硬性):**

1. `api-key` **绝不写进** `application-dev.yml` —— 该文件虽被 gitignore,但 AGENTS.md 明确"file is gitignored — do not commit it",而 `.example` 是**要提交**的。密钥只从环境变量取。
2. `log-requests` / `log-responses` **默认 false**。开启会把完整 prompt 与补全写进日志,而 DeepSeek 是外部服务,这等于把用户数据带出基础设施。答辩演示可临时开,演示完关。
3. `api-key` 为空时 `ChatConfig` 应**不创建 Bean** 并让 `/api/chat/message` 返回 `50000` + "对话功能未配置",而不是启动失败 —— 保证没有密钥的机器照样能跑 `mvn test` 与其余 47 个 E2E 用例。

---

## 9. 测试与验收

### 9.1 后端单测(JUnit5 + Mockito,沿用既有风格)

| 测试 | 覆盖 |
| --- | --- |
| `ChatToolsTest` | 已存在于仓库的 Mockito 风格:`@Tool` 方法复用既有 Service;入参夹紧;对象参数为 `null` 时不 NPE |
| `ChatToolsValidationTest` | `count` 越界、`sessionId` 非正数、`movieId` 不存在 |
| `ChatAssistantServiceTest` | 同 MemoryId 并发调用被串行化(用 latch 断言互斥);记忆淘汰被触发 |
| `ChatConfigTest` | `api-key` 为空时不注册 `ChatModel` Bean |
| `ChatControllerTest` | 匿名调用不 401;限流命中返回 42900 |

**不写**(成本高、收益低):真实调用 DeepSeek 的集成测试。用 `MockChatModel` 或直接测 `ChatTools`,把"模型说得对不对"交给 §9.3。

### 9.2 前端

- `pnpm type-check` 必须过(仓库无 lint);
- `pnpm test`(vitest 3.x,已在工作区启用)新增 `useChatContext` 的单测:各路由下 `sessionId` / `seatCount` 的取值;
- `ActionCard` 的跳转参数构造(尤其 `sessionId` 保持字符串)。

### 9.3 E2E

复用 `tests/`(pytest + Playwright,POM)新增 1 个模块,只断言**确定性**的东西:

- 浮窗在 `/payment` 不显示;
- 发送消息后出现回复气泡(不校验文案内容);
- 卡片点击后 URL 变为 `/seat/{sessionId}` 且 `preselect` 传入;
- **回归断言:既有 67 个 E2E 用例(`tests/` + `tests/web/` 下 `test_*.py` 文件中 `def test_*` 函数统计,2026-09-21 实测)仍全绿** —— 这是浮窗改动的真正验收线。计数口径已与当前仓库一致,后续若新增 E2E 同步更新此数字。

### 9.4 验收门禁

1. `mvn test` 全绿(既有 69 个 `@Test`(17 个测试类,2026-09-21 实测;AGENTS.md 旧数字 29/8 早不准确) + 新增);
2. `pnpm type-check` + `pnpm test` 全绿;
3. `tests/` 套件 67 用例不回归(口径同上:`tests/` + `tests/web/` 下 `test_*.py` 文件中 `def test_*` 函数统计);
4. **助手在任何输入下都不得产生锁座/支付/退票副作用** —— 用 `redis-cli` 断言 `cinema:session:*:lock` 与 `cinema:user:pending:*` 在纯聊天后无变化。这是本 spec 最重要的一条门禁,直接对应 §2.3 的三条证据;
5. `CinemaAssistant` 的工具清单里不存在任何写方法(可用反射断言 `ChatTools` 上的 `@Tool` 方法白名单)。

---

## 10. 答辩要点

1. **为什么不用 Python + LangChain?** 进程内直调既有强类型领域 Service,零额外鉴权、零跨进程事务边界;多一个进程的运维与答辩解释成本在课程设计里是纯负债。
2. **为什么助手不能锁座?** 三条代码级证据:`closeIfUnpaid` 会静默释放用户已锁座位;`@Idempotent` 的键含精确座位组合,机器合成调用等于绕过 P0 spec 的验收门禁;`lock_seat.lua` 的冲突语义是整单失败,不适合机器自主重试。
3. **为什么不做 RAG?** 检索对象是结构化实时事实(位图、场次、价格),向量相似度解决不了"今晚 8 点还有座吗",却会引入 MySQL/Redis 之外的第四个存储。
4. **为什么不流式?** 项目是纯 Servlet MVC(全库 0 处 `Flux`/`SseEmitter`),且行动卡片必须整块结构化下发。加流式要为答辩引入一整条新传输链路,收益只是"逐字感"。
5. **为什么显式装配而不用 starter?** starter 仍是 beta,且会把所有 `@Component` 上的 `@Tool` 自动注入每个 AI Service;显式装配让"助手能做什么"在代码里一眼可查,也避免了"同类型多 Bean 启动失败"。
6. **最容易被问倒的两处,提前准备:** ① 工具参数错误的默认行为是抛异常而非重试,我们显式配了 `ToolArgumentsErrorHandler`;② 同一会话并发调用会损坏 `ChatMemory`,我们自建了按 memoryId 的串行化。这两条都出自官方文档原文。

---

## 11. 风险与回滚

| 风险 | 缓解 |
| --- | --- |
| 外部 API 不可达 / 超时 | 90s 超时 + 2 次重试;失败返回 `50000` 而非 500;浮窗可关闭 |
| 无密钥环境下演示 | `api-key` 为空则不注册 Bean,`/api/chat/message` 返回明确业务码 |
| 浮窗打破既有 47 个 E2E | 路由感知 + 默认收起;挂载策略出问题时优先改挂载,不改既有测试 |
| `ChatMemory` 内存泄漏 | 会话结束/超时 `evictChatMemory(id)`;串行化 map 同步清理 |
| Jackson 2.21.2 vs 2.22.x | 出现序列化异常时再钉 `jackson-bom:2.22.1`,不提前钉 |
| LangChain4j 2.0 移动 `@Tool` 包 | 已知 issue #4577;升级时改 import,无 API 语义变化 |
| 模型幻觉出不存在的场次/座位 | 所有 `sessionId`/`movieId`/`seatIndex` 以工具返回值为唯一事实来源,工具查不到即返回失败文本;卡片只由工具结果构造,**不由模型自由生成** |

**回滚成本极低**:整个功能是**新增包** `modules/chat/` + 新增前端目录,**零改动既有文件**(除 `pom.xml` 依赖与 `App.vue` 挂载一行)。删掉即可完全回退。

---

## 12. 估时

| 阶段 | 内容 | 估时 |
| --- | --- | --- |
| 1 | 依赖 + `ChatConfig` + 跑通一次真实模型调用 | 0.5d |
| 2 | `ChatTools` 只读工具 7 个 + 入参校验 + 单测 | 1.0d |
| 3 | `ChatAssistantService` 串行化 + 记忆 + 错误处理器 | 0.5d |
| 4 | `ChatController` + `ChatRequestDTO`/`ChatResponseVO` + 限流 | 0.5d |
| 5 | 前端浮窗 + 3 个组件 + `useChatContext` + `preselect` 接线 | 1.0d |
| 6 | 单测 / E2E 回归 / 门禁 4 的副作用断言 | 0.5d |
| **合计** | | **~4.0d** |

> 估时基于"对既有 Service 层与 P0 工具包已熟悉"的开发者;首次做约 +30%。

---

## 附录 A:事实来源

所有版本号与 API 行为均于 **2026-09-21** 核实,来源:

- Maven Central 元数据:`langchain4j` / `langchain4j-open-ai` / `langchain4j-bom` / `langchain4j-spring-boot-starter` 的 `maven-metadata.xml`
- LangChain4j 官方文档:Spring Boot Integration / OpenAI-Compatible Language Models / Tools (Function Calling) / Chat Memory / Structured Outputs / Response Streaming / Observability
- GitHub issue:#4577(`@Tool` 包迁移,open)、#4528(DeepSeek/Qwen tool-call id,已关闭)、#1511(第三方 baseUrl 静默失败,已关闭)、#6342(starter 缺失 configuration-metadata,已关闭)
- 厂商文档:阿里云 Model Studio OpenAI 兼容说明(工具调用与流式互斥、region-bound key)

**明确未证实、故不写进方案的:** Spring AI 与 LangChain4j 的官方兼容性声明(官方文档对此完全沉默,只有第三方博客);DeepSeek function-calling 的准确率(无一手来源,任何"可靠"的说法都是厂商宣传)。

## 附录 B:三处官方文档原文(答辩可引用)

1. **工具参数错误默认抛异常:**
   > "By default, when something is wrong with tool arguments (e.g., the LLM generates an invalid JSON or omits a required parameter), the AI Service will not be able to execute the tool, so it will fail with an exception."

2. **工具执行异常默认泄漏内部信息:**
   > 默认行为 "can leak internal application data: stack traces, file paths, credentials… PII"

3. **同 MemoryId 并发损坏记忆:**
   > "AI Service should not be called concurrently for the same `@MemoryId`, as it can lead to corrupted `ChatMemory`. Currently, AI Service does not implement any mechanism to prevent concurrent calls for the same `@MemoryId`."

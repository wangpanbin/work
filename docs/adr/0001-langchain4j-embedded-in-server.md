# 1. 对话助手采用 LangChain4j 嵌入 cinema-server

- 状态:已接受
- 日期:2026-09-21
- 决策者:项目作者
- 相关:`docs/superpowers/specs/2026-09-21-chat-assistant-design.md`

## 背景

需要给影院抢票选座系统加一个对话式订票助手,能查影片/场次/余座/订单并给出可执行的座位建议。

系统现状:`cinema-server` 是**单模块** Spring Boot 3.5.4 / JDK 21 应用,已有 12 个 REST controller、强类型的领域 Service 层(`MovieService` / `SessionService` / `SeatService` / `OrderQueryService`)、自研的 `@RateLimit` / `@Idempotent` AOP 工具包、以及一套以 Redis Bitmap + Lua 为核心的抢票链路。

可选的实现语言有两条真实路径:Python 版 LangChain 独立服务,或 Java 版 LangChain4j 嵌入现有单体。

## 决策

采用 **LangChain4j 1.20.0 嵌入 `cinema-server`**,以进程内直调既有领域 Service 的方式暴露只读工具。

## 理由

1. **进程内直调,零胶水。** `ChatTools` 直接注入既有 Spring Bean。若选 Python,则需要为每个查询自造 HTTP 客户端、重复实现 JWT 解析、把 `{code,msg,data}` 错误码映射到 Python 异常,再映射回给模型 —— 全部是把已有能力翻译一遍的纯负债。
2. **不引入第二个运行栈。** Python 方案意味着第二个进程、第二套依赖管理、第二份配置与密钥、第二个部署单元。对本项目(课程设计答辩)而言,这些成本没有任何收益与之对应。
3. **领域 Service 已是强类型且带横切注解。** `@RateLimit` / `@Idempotent` 是 Java 侧的现实约束,助手需要理解并尊重它们 —— 同进程内这件事是可读的,跨进程就只能靠约定。
4. **无并发/事务边界问题。** 同进程内 `UserContext`(ThreadLocal)与既有事务语义可直接复用。

## 被否决的替代方案

- **Python + LangChain 独立服务**:生态更新是唯一优势;代价是多一个进程、多一层网络、多一套鉴权、多一份课程设计答辩需要解释的架构复杂度。在"已有成熟 Java 领域层"的前提下不成立。
- **Spring AI**:同为 Java 侧的 LLM 抽象,能力重叠。**注意:LangChain4j 官方文档对两者的关系、能否共存、是否有类路径冲突完全沉默,本项目没有找到任何一手来源的兼容性声明**(两者坐标与包名不重叠 —— `dev.langchain4j.*` vs `org.springframework.ai.*` —— 但混用属于无人验证过的领域)。本项目只选其一。
- **直接调裸 HTTP(不用任何框架)**:工具调用循环、schema 生成、记忆管理都要自己写,不划算。

## 后果

- `cinema-server/pom.xml` 增加 `langchain4j` + `langchain4j-open-ai` 两个依赖(不用 BOM,不用 Spring Boot starter,理由见 spec §3.2)。
- 需要接受一个 Java 生态的 LLM 框架,其 `@Tool` 等注解**已排入 2.0 里程碑的包路径迁移**(issue #4577),未来升级需要改 import。
- LangChain4j 传递 Jackson 2.22.x,而 Spring Boot 3.5.13 的 parent 把 `jackson-bom` 钉在 2.21.2 —— parent 会胜出并降级。这意味着 LangChain4j 未在 Spring Boot 的精确 Jackson 版本上测过。出现 JSON 序列化异常时的处置方式见 spec §3.2。
- 整个功能是新增包,回滚成本接近零:删除 `modules/chat/` 与前端目录即可。

## 备注

**最低 JDK 为 17**(官方明文),本项目 JDK 21 满足。
**版本雷区:`1.19.1` 是被误发布的版本,包含 1.20.0 之后的提交,Maven Central 不可撤回 —— 永远不要使用它**,要留在 1.19.x 请用 `1.19.3`。

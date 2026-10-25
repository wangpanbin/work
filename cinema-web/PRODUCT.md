# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**主用户 = 课程交付物的验收方**:课程老师 / 答辩评审。他们在**演示场景**里使用本项目 —— 看一条完整主链路能否走通,并重点验证「高并发下座位会不会卖错」。他们要的不是「能买票」,而是**可被验证的正确性证据**(压测结论、位图解码回归、状态机 round-trip)。

**产品内角色(次要,服务于上面的演示)**:

- 观众(购票用户):注册/登录 → 选片 → 选场次 → 抢座锁座 → 支付 → 取电子票 → 退票。
- 影厅运营(管理员,`role === 1`):影片/影厅/场次 CRUD、手动恢复位图、经营看板、实时数据大屏。

## Product Purpose

影院/演出抢票选座系统的**前端模块**,与 `../cinema-server/`(Spring Boot 3.5,8080)共同构成一个课程设计交付物。产品把一条真实影院交易链路完整实现:选片 → 选场次 → 实时选座 → 锁座(15 分钟待支付)→ 支付 → 取消/退票 → 电子票二维码,并在其上叠加管理端经营看板与实时数据大屏。

成功 = 评审能在一次可复现的演示里,既走通全链路体验,又能看到「并发抢同一座位 0 超卖」这类正确性证据。

当前以「星辉影城 / Star Cinema」为名呈现 —— 这是**当前状态,不是绑定约束**(用户确认)。

## Positioning

**在高并发下把座位卖对。**

差异化不在「能买票」(任何 CRUD 都能),而在**座位状态的正确性**:Redis Bitmap + Lua 原子锁座、DB CAS + 生成列唯一索引双保险、ZSet 延迟关单双保险、WebSocket 实时同步、位图冷启动重建。250 并发抢同一座位实测 0 超卖、任意瞬间同座位最多 1 张待支付单,是这套机制的直接产出。

辅助机制:对话式订票助手(LangChain4j + DeepSeek)在**只读 + 只建议**的边界内给出可执行的座位建议(ADR-0002),执行权始终留给用户,写入仍走既有锁座链路。

## Operating Context

- 前端**不能独立运行**:`pnpm dev`(5173)通过 `/api` 与 `/ws` 代理转发到 `http://localhost:8080`,后端 + MySQL 8 + Redis 8(127.0.0.1:6379)必须先起。
- 初始化库表按 `sql/01_schema.sql` → `sql/02_init_data.sql` → `sql/03_p0_increment.sql` 顺序执行;可选的 `sql/06_qa_knowledge.sql` 是对话助手的 FAQ 知识库(不跑则 `searchFaq` 降级,须跑完并重启后端),`sql/07_real_demo_movies.sql` 把演示影片换成真实片名与专属海报(需 `--default-character-set=utf8mb4`)。
- 演示/截图前必须跑 `sql/05_refresh_sessions_for_demo.sql` 补今天起 3 天的场次(幂等,可重复执行);影片详情页的日期选择器只看今天/明天/后天,窗口一过就是空场次。**不要重跑 `sql/04_extra_demo_data.sql`,它会静默失败。**
- 测试账号:`user1/123456`、`user2/123456`、`admin/123456`(后端启动时自动初始化)。
- 对话助手需 `DEEPSEEK_API_KEY` 环境变量;未设时 `/api/chat/message` 返 `50000` 短路(设计如此,不挂外部 key 也能起服务)。
- 无 Docker/RabbitMQ;延迟关单用 Redis ZSet 延迟队列。
- Vite dev 只绑 IPv6 `[::1]`:用 `http://localhost:5173` 访问,不要用 `127.0.0.1` 探测(会误判前端没起)。
- E2E/浏览器验证走 `playwright-cli` 技能。

## Capabilities and Constraints

**用户端**:注册/登录(JWT,注册确认密码)、影片列表/详情/搜索筛选、场次选择、WebSocket 实时选座、锁座下单(15 分钟待支付)、模拟支付、我的订单(取消/退票/电子票二维码)。

**管理端**:影片/影厅/场次 CRUD(含座位自动生成)、**场次分页**(`GET /api/admin/sessions/page`)、手动恢复位图(列表每行一个按钮)、经营看板(票房/订单/趋势/TOP 影片/上座率 + 营收导出 xlsx)、实时数据大屏。

**工程化**:`@RateLimit` 限流、`@Idempotent` 幂等、统一 `{code,msg,data}` 错误码、全局路由守卫(登录/管理员)、404 兜底、vue-i18n 11 中英双语。

**安全基线**(后端):JWT 密钥走 `JWT_SECRET` 环境变量、CORS 收敛成 `CORS_ALLOWED_ORIGINS` 白名单、登录按 IP+账号 10/分钟 + 注册按 IP 120/小时 限流、管理员播种双开关。

**约束(改动前必须知道)**:

- 雪花 ID 一律当 string 处理,避免 JS Number 精度截断。
- 主题色一律走 `src/styles/main.css` 的 `:root` token,禁止在 `.vue` 里写死色值;`rgba(var(--x-rgb), a)` 是非法 CSS,必须用斜杠语法 `rgb(var(--x-rgb) / a)`。
- 座位图 5 态转灰度后必须仍可分辨(已固化为 `themeTokens.test.ts` 的可执行断言)。
- `seatIndexes` 上界必须按场次的 `seatCount` 校验;越界索引会直接落进 `lock_seat.lua` 的 `SETBIT`,位图按 offset 立即分配且**永不回收**。
- **Redis 位图不会自动收缩**:清零高位后 `STRLEN` 不变,体积只增不减 —— 已撑大的位图要用 `tools/reconcile_seat_bitmap.py --purge-ghost-orders --fix` 重建(它是 DEL + 按合法位重写)。
- 退票释放座位必须**按订单幂等**(`refund_seat.lua` 的第三个 key):否则补偿任务二次释放会把**别人已付款的座位**清成可选。
- 位图重建必须走**合并(只加不减)**(`merge_seat_bitmap.lua`):合并结果 = 正式位图 OR DB 快照,座位只可能变不可选、绝不可能变可选(宁可锁死也不误放)。「先删正式位图再重建」会留下空位图窗口(超卖);「用 DB 快照 RENAME 覆盖」会抹掉快照与换名之间新产生的锁(同样是超卖)—— 合并必须在同一个脚本里原子完成。位图不再自动收缩,泄漏位用 `tools/reconcile_seat_bitmap.py --fix` 离线清。
- 对话助手的 `@Tool` 方法**不允许**出现 `userId` 形参,身份由服务端 `UserContext` 注入(`ChatToolsStructureTest` 反射锁死);任何写方法被 `@Tool` 注解即测试红。
- 前端 vitest 跑在 `environment: 'node'`,未装 jsdom / `@vue/test-utils`,挂不了 Vue 组件;视图层验证只有三条路:下沉纯函数、`.vue` 源码静态契约断言、真浏览器。
- 无 linter;`pnpm build` 与 `pnpm type-check` 查出的问题不重叠,两个都要跑。
- 路由级缓存(`movieCache`,5 分钟 TTL)不监听 Admin CRUD,管理员改完数据用户端最长 5 分钟内可能看到陈旧数据(与后端方案一致,不主动补救)。
- **属当前状态、非硬性要求**:中英双语、品牌名、用例数等;改 locale 或加 `t()` 后仍必须跑 `pnpm test`(`key-coverage` 是防运行时裸 key 的闸门)。
- **未设定产品级无障碍标准**:2026-10-12 审计发现的键盘支持缺失属已知缺陷,不是本项目被考核的验收项。(`lang` 那条**已修复** —— `i18n/index.ts` 的 `applyHtmlLang()` 在运行时同步 `document.documentElement.lang`,`index.html` 里的静态值只是首屏兜底。v0.3.0 又给大屏连接状态加了 `role="status" aria-live="polite"`、给行动卡片的内层按钮绑上焦点落点,但整体键盘可达仍未完成。)

## Evidence on Hand

- 测试:后端 JUnit5 + Mockito、前端 Vitest 3.x(`tests/**/*.test.ts`)、仓库内 Python 工具测试(`tools/test_*.py`)。**用例数会漂移,以实跑为准** —— AGENTS.md 明确写了「数字以实测为准,别照抄文档」。
- 上线前检查:`docs/部署前置检查.md` + `tools/check_redis_safety.py`(Redis 配置只读探针)。
- 压测:`docs/压测报告-v3.md`(一键演示生成,内容是该次运行的快照)、`docs/压测报告.md`、`docs/压测报告-v2.md`(历史快照);核心结论「250 并发抢同一座位 0 超卖」。
- 设计与决策:`docs/实现方案.md`、`docs/superpowers/specs/`、`docs/adr/`(ADR-0001/0002/0003)、`CONTEXT.md` 领域词表。
- 资产:`src/components/BrandMark.vue`(手写票根 SVG,无外部依赖)。
- 本地产物(已 gitignore,不构成依据):`test/`、根目录 `test_*.py`、`_report*`、`test/e2e-report-*.md`。
- **缺席(未来工作不得编造)**:没有真实用户、真实订单、客户评价、媒体报道或商业数据;演示数据全部由 seed 脚本生成。

## Product Principles

1. **正确性 > 功能量**:判断任何改动的第一问是「它会不会让座位卖错」;抢票链路上的取舍一律向一致性倾斜。
2. **证据来自实跑,不来自文档**:测试数、压测结论、位图行为都以现场跑一遍为准,漂移的数字不构成依据。
3. **交付物必须可复现**:任何演示都应当只靠测试账号 + `sql/05` 补种脚本就能从零走通,不依赖手工状态。
4. **写入权永远在用户手里**:助手只给建议卡片,锁座/支付/退票仍由用户在既有链路里执行;越权与静默状态变更都是红线。
5. **单一真相源**:颜色走 token,领域词走 `CONTEXT.md`,架构取舍走 ADR —— 同一事实不允许有两个互相漂移的副本。
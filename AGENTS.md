# AGENTS.md

Course-design project: cinema ticket flash-sale + seat-selection. Two collaborating modules in this repo:

- `cinema-server/` — Spring Boot 3.5 backend (JDK 21, MyBatis-Plus, Redis 8 + Lua, WebSocket), port **8080**
- `cinema-web/` — Vue 3 + Vite + TS + Element Plus + Pinia frontend, port **5173**

## Running locally

The frontend is useless on its own. `pnpm dev` proxies `/api` and `/ws` to `http://localhost:8080`, so the backend + MySQL + Redis must be up first.

1. Init DB (one-time): `mysql -uroot -p < sql/01_schema.sql`, `02_init_data.sql`, `03_p0_increment.sql` (in repo root `sql/`). Optional: `04_extra_demo_data.sql` (12 more demo movies + sessions; needs `--default-character-set=utf8mb4`), `05_refresh_sessions_for_demo.sql` (**before any demo/screenshot**: regenerates today+1+2 sessions; idempotent), `06_qa_knowledge.sql` (FAQ knowledge base for the chat assistant — **the table won't exist and `searchFaq` degrades until you run it and restart**).
2. Backend: `cd cinema-server && copy src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml` then edit in MySQL password. File is gitignored — do not commit it. Start with `mvn spring-boot:run`.
3. Frontend: `cd cinema-web && pnpm install && pnpm dev` → http://localhost:5173
4. **对话助手(可选)**: 设 `DEEPSEEK_API_KEY` 环境变量 → 后端启动时 `cinema.chat.api-key` 从 `${DEEPSEEK_API_KEY:}` 解析(`application.yml` 已配)。未设时 `/api/chat/message` 返 `50000` + "对话功能未配置"(这是设计:不挂外部 key 也能让 server 起来);设了之后接通 LLM,前端 ChatWidget 可用。USER 级 setx 即可,cmd/PowerShell 需重启继承。

Local middleware: MySQL 8 (local), Redis 8 at **127.0.0.1:6379**. No Docker/RabbitMQ — the delayed-close uses a Redis ZSet delay queue.

Test accounts seeded by backend on startup: `user1/123456`, `user2/123456`, `admin/123456`.

## Commands

- Frontend: `pnpm dev` · `pnpm build` · `pnpm type-check` (`vue-tsc --noEmit`) · `pnpm test` (vitest 3.x, `tests/**/*.test.ts`,**407 用例跨 30 个 `.test.ts` 文件**,2026-10-19 实测;上一次 317/22)。本轮新增 `tests/styles/designDocSync.test.ts`(6 用例,锁 `DESIGN.md` front-matter 色板与 `main.css` 逐值一致 + 正文里每个 hex 都必须是真实 token 的值)与 `tests/utils/seatDragRange.test.ts`(12 用例,锁拖拽连选的同行连续区间/不可选截断/4 座上限);`tests/styles/themeTokens.test.ts` 新增 3 个 describe(场景身份色家族 5 条 + 域隔离 2 条 + 禁入全局强调面 1 条),见下方「场景身份色」。上一轮新增 `tests/components/chat/orderProposalCard.test.ts`(22 用例,锁一键下单卡的倒计时/过期态/确认请求体不含座位 + 错误码映射表与双语 key 存在性)。再上一轮新增 `tests/stores/seatWatch.test.ts`(35 用例)、`tests/utils/ws.test.ts`(6 用例,锁断连重连状态机,见下方坑)与 `tests/styles/themeTokens.test.ts` 的 3 条关注标识契约断言。`tests/styles/themeTokens.test.ts` 是换肤闸门,见下方「主题 token」。First test file: `tests/views/order/constants.test.ts` (**16 用例**覆盖 ORDER_STATUS 5 态 + 跨态 round-trip). `tests/utils/markdown.test.ts` 是 P2-2 聊天 Markdown 渲染的 24 用例,其中一组专门锁 XSS(先转义后拼标签).
- Backend: `mvn spring-boot:run` · `mvn test` (**271 JUnit5+Mockito unit tests across 43 classes**,2026-10-19 实测;上一次 223/36,再上一次 217/36,再上一次 178/33,早期数字 29/8 → 69/17 → 105/24 → 118/24 → 115/25 → 127/26 → 139/26 过期).ChatAssistantServiceTest 新增 4 条用例锁 **chatSessionId 缺省兜底**(spec §6.1:后端拿 null/blank 生成 fallback id 并回传 —— 不兜底会 NPE→50000「系统繁忙」,2026-10-19 联调实测踩过).一键下单轮新增的 3 个类: `OrderProposalServiceTest`(17 用例,座位/价格以服务端为准 + 存量待支付单 + 一次性消费 + IDOR + 票数以 count 为准)、`OrderProposalAssemblerTest`(7 用例,既有卡原样透传 + 失败丢卡)、`ChatOrderControllerTest`(9 用例,座位来自快照 + 门禁注解反射断言)。上一轮新增的 3 个类锁的是 P0 修复: `OrderCoreSeatRangeTest`(`seatIndex` 必须落在场次 `[0, seatCount)` 否则 40001)、`LockSeatsDTOValidationTest`(元素级 `@NotNull/@Min/@Max`)、`PageParamsTest`(分页钳制)。Seat Watch 轮新增 `SeatEventPublisherTest`(9 用例)锁事件序号注入,含「自增失败降级为 seq=0」与「序列化失败不外抛」。缺陷修复轮新增 `OrderTimeoutCompensateJobTest`(3 用例,锁卡死退款单降级为已取消时**必须释放座位** —— 座位是已售态, 订单死后没人再碰它)。
- There is no linter configured in either module. **build 要单独验**:`pnpm build` 与 `pnpm type-check` 查出的问题不重叠(SCSS 变量/模板编译错误只有 build 报)。
- **数字以实测为准**:README/AGENTS 里的用例数会随测试增长漂移,别照抄文档 —— 跑一次 `mvn test`(或聚合 `target/surefire-reports/TEST-*.xml`)和 `pnpm test` 取真值再更新。

## Conventions & gotchas
- **主题色一律走 `src/styles/main.css` 的 `:root` token,禁止在 `.vue` 里写死色值**。`tests/styles/themeTokens.test.ts` 用静态扫描锁这条:扫 `src/**` 的 `*.vue` / `*.ts`(先剥注释)找 `#rrggbb` 与 `rgba(数字`,白名单有且只有两类:① **自定义属性声明行**(`--x: #hex`,即 token 定义本身——`SeatSelect.vue` 的 `--seat-*`、`AdminHome.vue` 的 `--aside-rule` 属此类,能引用 `var(--hall-*)` 等 :root token 就别手抄);② `Payment.vue` 二维码三处(`#fff` / `#111827` / `#ffffff`,扫码硬要求)。白名单外直接判红。**改色只改 token,不要去改组件。**
- **换主题的最小改动面就是 `:root` 那几组 token** —— `Dashboard.vue` / `Login.vue` / `AdminHome.vue` 已全部 token 化,主色一改,折线图、面积渐变、票房柱、选座高亮、header chip 全站自动跟随。当前色板(2026-10-19 视觉重构后):主色 `#2563eb`(唯一**全局**强调色)、内容区冷灰白 `#eef1f6`、墨色 `#0f172a`、管理端侧栏深靛蓝 `#0f172a`(与座位图暗场 `--hall` 同值,构成一个暗色家族),外加**每条业务域一支的场景身份色**(`--scene-browse` 橙红 / `--scene-seat` 青),见下方「场景身份色」。
- **场景身份色(2026-10-19 新增,`cinema-web/DESIGN.md` 的 The Scene Ink Rule)**:全局印色仍然只有一支, 但每条业务域另有一支自己的身份色, 只在该域的**小面积局部元素**上出现(徽标/圆点/进度条/图表系列/票面印章/图标底色)。当前两族:`--scene-browse`(橙红,域 = `Home`/`MovieDetail`)、`--scene-seat`(青,域 = `SeatSelect`/`SeatItem`), 每族恰三档 `base`/`ink`/`rgb`。
  - **禁入全局强调面**(主 CTA / 链接 / 焦点环 / 章节墨条 / 卡片与表格描边 / 导航激活态)与**禁出本域**, 两条都由 `themeTokens.test.ts` 的新 describe 断言 —— 加第二支色时别用 `--accent-` 前缀(既有断言数 `--accent*` 的条数), 也不要用改既有断言的方式绕过。
  - `base` 只做图形, **不得用作正文文字**; base 底上的字用 `--ink`(墨色), 不用 `--ink-inverse` —— base 都是高明度高饱和色, 反色字压在上面读不清。
  - `ink` 必须在 `--paper`/`--paper-raised`/`--paper-sunk`/管理端纸面四种底上都 ≥4.5:1(实测 `#9a3412` 6.45:1、`#155e75` 6.41:1)。
  - 身份色与 `--accent` 的**色相差 ≥25°**(同屏必须可分辨); 与**同域**语义色靠「灰阶差 ≥25 **或** 饱和度差 ≥0.25」区分(语义色低饱和深色, 身份色高饱和亮色, 靠饱和度分层而不是靠色相)。
- **⚠️ 改色必须同时改 `cinema-web/DESIGN.md` 的 front-matter 与 `src/styles/main.css`** —— 两者存的是同一份色板, `tests/styles/designDocSync.test.ts` 会逐值校验并判红(这是为了堵住「文档说有, 代码没有」的静默漂移)。`DESIGN.md` front-matter 的 `spacing` 段标了 `docOnly: true`(间距 token 至今不存在于 CSS, 间距仍是各 `.vue` 内写死的 px)。
- **座位图的拖拽连选是纯视图层实现**(`utils/seatDragRange.ts` 纯函数 + `SeatItem` 的 `pointerdown`/`pointerenter` 两个 emit + `SeatSelect` 的组件本地 ref), 落座仍走既有的 `seatStore.toggle(idx)` —— **`stores/seat.ts` 一行没改**, 上限/冲突/乐观更新全由 store 说了算。预览环**只画 `outline`**: 不得新增 `--seat-*` 变量(themeTokens 断言那 13 个, 多一个就红), 也不得改座位底色(5 态灰度契约锁的是底色)。区间只取**同行连续可选**, 撞到不可选座位即截断; 落座前再核一次当前状态(拖拽期间 WS 可能已经改过)。
- **⚠️ `rgba(var(--x-rgb), a)` 是非法 CSS,浏览器会静默丢弃整条声明**(2026-10-12 踩过:侧栏激活态底色因此变 transparent,页面看着像没上色)。`rgba()` 的逗号形式要求 R/G/B 也用逗号分隔,而 token 里存的是空格分隔。**正确写法是斜杠语法 `rgb(var(--x-rgb) / a)`**。themeTokens.test.ts 有一条断言专门锁这个。
- **座位图 5 态转灰度后必须仍可分辨** —— 这是 `SeatSelect.vue` 的既有验收硬要求,现已固化成 `themeTokens.test.ts` 里的可执行断言(相邻灰度差 ≥ 2)。**改 `--seat-*` 任何一色前先跑这条**;实测 `--seat-mine-bg: #2f6b4f` 动一下间隔就掉到 1。
- **中文注释句尾一律用全角 `。`,不用半角 `.`**(2026-10-19 统一)。仓库主流风格是全角(统一前 809 行全角 vs 255 行半角,45 个文件内部两种混用)。**只管中文句尾,技术性 `.` 一个都不能动**:`.java` / `.ts` / `.xlsx` 等扩展名、`45.00` 小数、`v0.1.0` 版本号、`§4.5` 式引用、`e.g.` / `i.e.`、纯英文句末的 `.`、以及 `... . 复杂后缀` 这类。落地做法:**只替换注释正文最后一个 `.`**,不要正则全量替换 `.` → `。`。写文件时注意 `Read` 工具渲染的 `。` 可能实际是 ASCII `.`,锚点请用 Python `repr()` 验字节。
  > **2026-10-19 复盘(两条教训)**:① 第一轮清扫只覆盖 `src/main` + `cinema-web`,`cinema-server/src/test/**` 一个字没动,而这里却写下了「全仓 0 处」——**承诺必须小于等于事实**,否则这条规则本身就是一句假话;② 清扫是**一次性**的,清扫之后新写的文件又带回了半角(本轮 6 处:3 处新测试的 javadoc 首行 + 3 处 system prompt),所以「清扫已完成」不等于「以后都干净」,**新文件仍要自己写对**。补扫后全仓 tracked 文件只剩 3 处半角句尾,全在 AGENTS.md 自己的英文句子里(按约定豁免)。

- **前端 vitest 跑在 `environment: 'node'`, 且没装 jsdom / `@vue/test-utils`**(`vite.config.ts` 的 `test` 段 + `package.json` devDependencies)。**挂不了 Vue 组件**, 涉及视图层的测试只有三条路: ① 把判断逻辑下沉到纯函数/composable 测(如 `usePosterFallback`); ② 对 `.vue` 源码做静态契约断言(`tests/i18n/key-coverage.test.ts` 扫 `t()`、`tests/views/posterWiring.test.ts` 断言 `@error` 接线, 都是这一类, 脆性写在文件头注释里); ③ 上 Playwright 驱动真浏览器。**jsdom 也测不出真实渲染** —— `white-space` 这类 CSS 级联结果在 node/jsdom 下都取不到, 只能靠浏览器验证, 别用静态断言冒充渲染验证。也别为了"能挂组件"随手改 `environment`, 会波及全部 19 个测试文件。

- **pnpm v11 blocks dependency postinstall scripts** by default; `pnpm-workspace.yaml` whitelists `esbuild` and `vue-demi` via `allowBuilds`. If installing/skipping builds behaves oddly, this is why.
- Management API requires `role === 1`; the frontend route guard in `cinema-web/src/router/index.ts` blocks non-admins and redirects to `/` with `? _denied=1`. **`_denied` 的唯一消费者是 `App.vue` 的 watcher**(弹 `app.adminDenied` 提示后用 `router.replace` 把参数摘掉)——删掉那个 watcher 就会退回"静默弹回首页"。
- **对话助手的身份参数一律服务端注入**:`ChatTools` 的任何 `@Tool` 方法都**不允许**出现 `userId` 形参,身份取自 `UserContext`。`@P` 参数由 LLM 合成,交给模型填 userId 就是 IDOR 越权(见 `test/e2e-report-2026-09-29.md` P0-1,匿名曾可读全站订单)。`ChatToolsStructureTest#noToolExposesUserIdParam()` 用反射锁死这条红线。
- **一键下单的座位只走服务端快照**:对话助手确认下单时,座位与价格来自 Redis 里的提案快照(`cinema:chat:proposal:{id}`),**前端与模型都不回传座位**。改动这条链路时别把座位加进确认请求体 —— 那等于把 ADR-0002 当初反对的「模型决定锁哪个座」又放回来。`ChatMessage.vue` 的 v-for key 要用 `card.cardId ?? \`${card.sessionId}-${idx}\`` —— 装配器丢卡后索引会位移,用稳定 key 才不会让 Vue 复用错 DOM。
- **位图三脚本语义不可混用**:`release_seat.lua` 只清"未售出的锁定位"(待支付单取消/超时用)· `confirm_seat.lua` 置 sold 并同步清 lock · **`refund_seat.lua` 同时清 sold+lock**(退票专用)。退票误用 `releaseSeats` 会导致座位 sold 残留、永久不可再售(P1-1)。改任一脚本后请跑一次"锁座→支付→退票→重新锁座"位图解码回归。
- **⚠️ Redis 位图的位序是「字节内 MSB 优先」** —— 字节 `0xC0` 的第 0、1 位为 1(不是第 6、7 位)。seat-map API 的 `lockBitmap` 与 `utils/bitmap.ts`(`bit = 7 - (i & 7)`)用的就是同一套编码。2026-10-19 写对账工具时按 LSB 解,把 2 个真位读成 224 个假位,差点把「压测脚本污染」当成「全站漂移」。另外 `redis-cli` 取值有两个坑:`--no-raw` 给的是转义文本(带引号),`--raw` 给原始字节但**会补一个行尾换行**(凭空多出 2 个位)—— 正确姿势是 `STRLEN` 取真实字节数再截断。
- **位图与订单状态的对账工具**:`python tools/reconcile_seat_bitmap.py` —— 比对 Redis 位图与 DB 里 PENDING_PAY/PAID 订单的座位,报「泄漏(位图占着但 DB 无有效订单)」与「缺失(DB 说该占但位图是空的)」。默认只查有订单的场次,`--include-orphan` 连孤儿位图一起看,`--fix` 按 DB 权威清掉泄漏位。**位图漂移只能靠它发现** —— 肉眼在 140 座的图上看不出来。
- **锁座失败/冲突的提示权在调用方,不在 axios 拦截器**(`api/request.ts` 的 `skipErrorToast`):拦截器无条件弹一次通用文案,业务层再弹一次本地化的,同一次失败就弹两条(而且通用那条是后端中文原文,英文界面就夹中文)。自己弹提示的请求必须声明 `skipErrorToast: true`。通用错误文案统一走 `i18n/errorText.ts`(中文界面透出后端原文,其它界面用本地化兜底)。
- **WS 座位事件不带发起人**(`SeatEventPublisher` 只发 type/seats/seq),所以客户端无法从事件本身区分「我锁的」与「别人抢的」。做法是**锁座请求发出前先认领**(`seatStore.claimMyLocked`),让自家锁座的 WS 事件早到也不算冲突;reducer 的 `computeApplyEvent` 收了 `myLockedSet` 参数来做这个区分。漏了认领就会弹「你已选的 N 个座位被他人锁定」(2026-10-19 联调 F2)。
- **`seatIndexes` 必须按场次校验范围, 上界不能用常量** — `LockSeatsDTO` 的 `@Max(4095)` 只是兜底, 真实上界是场次的 `seatCount`(1 号厅 96 / 2 号厅 140), 由 `OrderCore#requireSeatsInRange` 在进 Lua 之前拦住。**越界索引不会被业务拒绝, 而是直接落进 `lock_seat.lua` 的 `SETBIT`** —— Redis bitmap 按 offset 立即分配且永不回收(实测 offset=100000 把位图从 18B 撑到 12501B, 公开的 `GET /seat-map` 响应放大 61 倍; 硬上界 2^32-1 时单 key 512MiB)。`SessionBitmapReader.readWithPadding` 另有读侧截断兜底历史脏位图。改动这条链路请同时跑 `OrderCoreSeatRangeTest` + `LockSeatsDTOValidationTest` + `test/stability_edge_probe.py`。
- **`_denied` / `test/` 下的报告文件**:E2E 报告见 `test/e2e-report-2026-09-29.md`(2026-09-29 的 14 个 bug 已全部修复,`git log` 可查对应 commit)。
- Auth state lives in `localStorage` under `cinema_token` / `cinema_user`; the axios interceptor (`src/api/request.ts`) reads the token and, on `40101/40102`, clears both keys and redirects to `/login`.
- Backend API responses wrap as `{ code, msg, data }`; `code === 0` is success. Distinct codes: `40002` seat-conflict (conflicting seats in `data`), `40003` 对话下单提案已过期/已被消费, `40004` 对话下单功能被回滚闸门关掉, `40900` idempotent conflict, `42900` rate-limited, `40101/40102` auth. Frontend axios unwraps `data` on success.
  - **`40002` 一码多义是踩过的坑** —— 提案过期原本也用 `40002`,前端按 spec §5.3 的表把过期显示成「座位被抢走了」。现已拆出 `40003`/`40004`,前端按 `err.code` 分支(映射表 `CONFIRM_ERROR_KEYS` 在 `components/chat/orderProposalCard.ts`,有单测兜底)。
  - **`request.ts` 的 40101/40102 分支也要挂 `err.code`** —— 那条分支提前 `Promise.reject(new Error(body.msg))`,不挂 `.code` 的话,上层所有 `code === 40101` 的判断都走不到(确认卡的「请先登录后再下单」就是这么变成死代码的)。其它码走通用分支(`err.code = body.code`)所以正常。
- WebSocket paths: `/ws/seat/{sessionId}` (public, auto-reconnect + 15s heartbeat in `src/utils/ws.ts`, intentionally hits port 8080 directly, not the vite proxy) and `/ws/admin` (demo-public).
- **⚠️ `ws.ts` 的 `connect()` 绝不能无条件写 `status='connecting'`**（2026-10-19 浏览器验收实测出的真 bug）。`SeatSelect.vue` 靠 `s === 'open' && prev === 'closed'` 判定「这是一次断线重连」，从而触发权威重拉；但 `connect()` 每次都先写 `'connecting'`，`onopen` 时 `prev` 恒为 `'connecting'`，那个判定**永远为假** —— 断连期间丢失的事件一次都不会被补偿，用户会一直看着一张过期座位图而毫无察觉。症状极具迷惑性：状态条正常回到「实时同步中」，但网络面板里**一条 `GET /seat-map` 都没有**。修法是新增纯函数 `statusOnConnect(everOpened)`：首次建连返回 `'connecting'`，重连保持 `'closed'`，让 `closed → open` 这一跳专门代表重连。`tests/utils/ws.test.ts` 的「重连态必须与首连态不同」就是这条 bug 的最小复现。**改动 `ws.ts` 的状态赋值时，先跑那条断言。**
- **同一用户同一场次只能有一个待支付单**（`RedisKeys.userPending(userId, sessionId)`，TTL=支付窗口 900s）。用 user2 造测试数据时，**第二笔锁座会把前一笔待支付单顶掉并自动取消**，于是先建的那几单 `status` 立刻变 2（已取消）、座位跟着释放。做「多座位同时释放」之类的验收必须**一笔订单锁 4 个座位**（`POST /orders/lock` 上限 4 座），不能一单一座地循环。另外 `@RateLimit` 会在连打 5~6 次 `POST /orders/lock` 后返回 `42900`，脚本里要留间隔。

## 座位事件序号与座位关注（2026-10-19 新增）

- **座位事件带全局单调序号**：`SeatEventPublisher` 发布前对 `cinema:seat:event:seq`（`RedisKeys.SEAT_EVENT_SEQ`）做一次 `INCR`，结果写进事件载荷的 `seq`。三个 `publishXxx` 的公开签名没变，所以 `SeatRedisSubscriber`/`SeatWsHandler` 零改动、四个下游 `@Mock` 测试类不受影响。
- **序号是可选字段**：`parseWsMessage` 只在 `seq` 是有限数值时才挂到事件对象上，缺失/非法**剥掉**该字段而不是挂 `seq: 0`。前后端版本不一致时前端退化成纯增量行为。
- **`seq <= 0` 必须走「无序号、总是应用」分支**：服务端 `INCR` 失败时兜底发 `seq=0`，若前端按 `0 > lastSeq[s]` 过滤会**把所有事件静默丢弃**。这是 `filterBySeq` 的第一条断言。
- **序号只能按座位比较**：`stores/seat.ts` 的 `lastSeq` 是**按座位索引寻址的数组**，不是整场次一个值。两个并发请求拿到 seq=5/6，谁先 PUBLISH 不确定；若用整场次单值，迟到的跨座位事件会被判陈旧而整条丢弃，那个座位的状态变更彻底丢失。`tests/stores/seatEventReducer.test.ts` 里有一条专门锁这个的用例。
- **`lastSeq` 在同场次重拉时不得重置**：重置会让重拉前仍在途的旧事件被重新应用，把刚刷新的权威位图改回旧状态。`load()` 只在换场次或 `seatCount` 变化时重建。
- **权威重拉**：`SeatSelect.vue` 在「释放事件之后」与「WS 重连成功之后」各触发一次防抖（500ms）的 `GET /api/sessions/{id}/seat-map`，失败退避 300ms/1s/3s，三次仍失败才在状态条提示。`load(m, {preserveSelection:true})` 不清空用户已选座位，而是逐个复核、只剔除确实被占走的。**序号不能替代它** —— 取序号与发送不原子，理论上仍有一次短暂假可选。
- **座位关注(Seat Watch)**：纯客户端、不占位、不落库、刷新即失，上限 8。命中释放提醒后自动结束一次性关注。判断逻辑全在 `stores/seatWatch.ts` 的纯函数里（`computeToggleWatch` / `computeReleasedWatchHits` / `computeRevalidateSelection`）—— 本仓 vitest 挂不了 SFC，只能这么测。
- **关注标识只用角标字形**（`.seat.watched::after`），不改座位背景/描边色。`themeTokens.test.ts` 有一条契约断言专门守这条：引入第 6 种座位色会与既有 5 态争抢明度层级，把「去色后仍可分辨」的间隔压回 1 级。
- Seat locking/paying/cancelling are decorated with `@RateLimit` + `@Idempotent` on the server; the frontend must handle the resulting error codes (e.g., refresh seat map on `40002`).
- **Vite dev server binds IPv6 `[::1]` only** — probing `http://127.0.0.1:5173` returns connection-refused and looks like the frontend is down when it is actually fine. Use `http://localhost:5173`. `Test-NetConnection -Port 5173` can also report False while the server is up.
- **Session listing filters by date; the frontend only ever asks for today/tomorrow/day-after** — `GET /api/movies/{movieId}/sessions` without `?date=` returns an **empty array** unless sessions exist today. `MovieDetail.vue` builds `dateOptions` from `dayjs().add(0|1|2,'day')` and defaults `selectedDate` to **tomorrow**, so if the DB has no sessions in that 3-day window every movie page degrades to `<el-empty>暂无场次` and the seat-selection demo is undemoable. This is data, not a broken endpoint.
- **⚠️ Do NOT re-run `sql/04_extra_demo_data.sql` to refresh demo sessions — it fails silently.** Its deterministic IDs are `movie_id*1e7 + hall_id*1e5 + d*100 + s` where `d` is the **relative day offset** (1..14), with no absolute date in the key. Once it has run, every `d` is already occupied; re-running later recomputes the *same* IDs, and its `INSERT IGNORE` drops every row — **the script reports success while inserting nothing**. Use `sql/05_refresh_sessions_for_demo.sql` instead: it encodes `DAYOFMONTH*100 + slot` (absolute-date IDs), is genuinely idempotent (same day → same IDs → all skipped), and generates 3 days × 4 slots for every `status=1` movie with `id<1000` into halls 1/2. Verified 2026-10-11: 897 → 1077 sessions, 60/day × 3 days, 12 per movie.
- **New sessions need no Redis bitmap warm-up** — `SeatService.seatMap` reads via `readWithPadding(key, seatCount)`, so a session with no Redis key simply returns an all-zero (fully selectable) seat map. Don't hand-write `SETBIT` to "prepare" a fresh session.
- **Seed only halls 1 and 2** — they are the only halls with `seat` rows (96 / 140 seats). Halls 3 and the VIP box carry snowflake IDs and are test artifacts.
- Business/architecture details live in `docs/实现方案.md` and `docs/superpowers/specs/`. Root `README.md` has the full API table, directory map, and verified end-to-end behavior.
- `test/`, root `test_*.py`, `_report*`, logs, and `application-dev.yml` are gitignored local artifacts — not source of truth.
- E2E/browser work is done via the `playwright-cli` skill.

## Architecture orientation

Frontend (all under `cinema-web/src/`): `api/` axios modules · `router/index.ts` global guards · `stores/` Pinia (user/seat/movieCache) · `utils/ws.ts` seat WebSocket · `utils/bitmap.ts` seat bitmap parsing · `i18n/` vue-i18n 11 instance (`index.ts` createI18n legacy:false, `detect.ts`, `persist.ts`, `fallback.ts`, `locales/{zh-CN,en-US}.ts`) · `composables/useChatContext.ts` · `constants/auth.ts` · `components/chat/` (ChatWidget, ChatMessage, ActionCard) · `views/` pages plus `views/admin/` management + dashboard/live. Seat selection is the core flash-sale flow (`views/SeatSelect.vue` + `stores/seat.ts`).

## Agent skills

### Issue tracker

GitHub Issues (via the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout. See `docs/agents/domain.md`. `CONTEXT.md` currently covers the **revenue-export** vocabulary plus the **seat-selection** terms (座位索引 / 选座 / 锁座 / 锁座冲突 / 待支付订单 / 场次上下文 / 行动卡片). Decisions live in `docs/adr/`.

### 对话式订票助手 (chat assistant)

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

### 中英文双语 (i18n)

Design spec: `docs/superpowers/specs/2026-09-22-i18n-zh-en-design.md`. vue-i18n 11 with `legacy: false`, two dictionaries (`zh-CN` / `en-US`), browser-language detection on first visit, localStorage persistence afterwards (user choice beats browser language), `fallbackLocale: 'zh-CN'`.

**`tests/i18n/key-coverage.test.ts` is the safety net for missing keys** — it statically scans `t('...')` usages and diffs both dictionaries, so it catches the case where a key is missing from *both* locales (which `fallbackLocale` cannot rescue; the page would render the raw key). **Run `pnpm test` after touching any locale file or adding a `t()` call.** Also update both `locales/*.ts` together — `locales-shape.test.ts` enforces structural parity.

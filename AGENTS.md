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

- Frontend: `pnpm dev` · `pnpm build` · `pnpm type-check` (`vue-tsc --noEmit`) · `pnpm test` (vitest 3.x, `tests/**/*.test.ts`,**223 用例跨 19 个 `.test.ts` 文件**,2026-10-12 实测;上一次 216/19)。新增 `tests/styles/themeTokens.test.ts`(46 用例)是换肤闸门,见下方「主题 token」。First test file: `tests/views/order/constants.test.ts` (**16 用例**覆盖 ORDER_STATUS 5 态 + 跨态 round-trip). `tests/utils/markdown.test.ts` 是 P2-2 聊天 Markdown 渲染的 24 用例,其中一组专门锁 XSS(先转义后拼标签).
- Backend: `mvn spring-boot:run` · `mvn test` (**169 JUnit5+Mockito unit tests across 32 classes**,2026-10-11 实测;上一次 150/29,早期数字 29/8 → 69/17 → 105/24 → 118/24 → 115/25 → 127/26 → 139/26 过期). 本轮新增的 3 个类锁的是 P0 修复: `OrderCoreSeatRangeTest`(`seatIndex` 必须落在场次 `[0, seatCount)` 否则 40001)、`LockSeatsDTOValidationTest`(元素级 `@NotNull/@Min/@Max`)、`PageParamsTest`(分页钳制).
- There is no linter configured in either module. **build 要单独验**:`pnpm build` 与 `pnpm type-check` 查出的问题不重叠(SCSS 变量/模板编译错误只有 build 报)。
- **数字以实测为准**:README/AGENTS 里的用例数会随测试增长漂移,别照抄文档 —— 跑一次 `mvn test`(或聚合 `target/surefire-reports/TEST-*.xml`)和 `pnpm test` 取真值再更新。

## Conventions & gotchas
- **主题色一律走 `src/styles/main.css` 的 `:root` token,禁止在 `.vue` 里写死色值**。`tests/styles/themeTokens.test.ts` 用静态扫描锁这条:扫 `src/**` 的 `*.vue` / `*.ts`(先剥注释)找 `#rrggbb` 与 `rgba(数字`,白名单有且只有两类:① **自定义属性声明行**(`--x: #hex`,即 token 定义本身——`SeatSelect.vue` 的 `--seat-*`、`AdminHome.vue` 的 `--aside-rule` 属此类,能引用 `var(--hall-*)` 等 :root token 就别手抄);② `Payment.vue` 二维码三处(`#fff` / `#111827` / `#ffffff`,扫码硬要求)。白名单外直接判红。**改色只改 token,不要去改组件。**
- **换主题的最小改动面就是 `:root` 那几组 token** —— `Dashboard.vue` / `Login.vue` / `AdminHome.vue` 已全部 token 化,主色一改,折线图、面积渐变、票房柱、选座高亮、header chip 全站自动跟随。当前色板(2026-10-12 换肤):主色 `#2563eb`(唯一强调色)、内容区冷灰白 `#eef1f6`、墨色 `#0f172a`、管理端侧栏深靛蓝 `#0f172a`(与座位图暗场 `--hall` 同值,构成一个暗色家族)。
- **⚠️ `rgba(var(--x-rgb), a)` 是非法 CSS,浏览器会静默丢弃整条声明**(2026-10-12 踩过:侧栏激活态底色因此变 transparent,页面看着像没上色)。`rgba()` 的逗号形式要求 R/G/B 也用逗号分隔,而 token 里存的是空格分隔。**正确写法是斜杠语法 `rgb(var(--x-rgb) / a)`**。themeTokens.test.ts 有一条断言专门锁这个。
- **座位图 5 态转灰度后必须仍可分辨** —— 这是 `SeatSelect.vue` 的既有验收硬要求,现已固化成 `themeTokens.test.ts` 里的可执行断言(相邻灰度差 ≥ 2)。**改 `--seat-*` 任何一色前先跑这条**;实测 `--seat-mine-bg: #2f6b4f` 动一下间隔就掉到 1。

- **前端 vitest 跑在 `environment: 'node'`, 且没装 jsdom / `@vue/test-utils`**(`vite.config.ts` 的 `test` 段 + `package.json` devDependencies)。**挂不了 Vue 组件**, 涉及视图层的测试只有三条路: ① 把判断逻辑下沉到纯函数/composable 测(如 `usePosterFallback`); ② 对 `.vue` 源码做静态契约断言(`tests/i18n/key-coverage.test.ts` 扫 `t()`、`tests/views/posterWiring.test.ts` 断言 `@error` 接线, 都是这一类, 脆性写在文件头注释里); ③ 上 Playwright 驱动真浏览器。**jsdom 也测不出真实渲染** —— `white-space` 这类 CSS 级联结果在 node/jsdom 下都取不到, 只能靠浏览器验证, 别用静态断言冒充渲染验证。也别为了"能挂组件"随手改 `environment`, 会波及全部 19 个测试文件。

- **pnpm v11 blocks dependency postinstall scripts** by default; `pnpm-workspace.yaml` whitelists `esbuild` and `vue-demi` via `allowBuilds`. If installing/skipping builds behaves oddly, this is why.
- Management API requires `role === 1`; the frontend route guard in `cinema-web/src/router/index.ts` blocks non-admins and redirects to `/` with `? _denied=1`. **`_denied` 的唯一消费者是 `App.vue` 的 watcher**(弹 `app.adminDenied` 提示后用 `router.replace` 把参数摘掉)——删掉那个 watcher 就会退回"静默弹回首页"。
- **对话助手的身份参数一律服务端注入**:`ChatTools` 的任何 `@Tool` 方法都**不允许**出现 `userId` 形参,身份取自 `UserContext`。`@P` 参数由 LLM 合成,交给模型填 userId 就是 IDOR 越权(见 `test/e2e-report-2026-09-29.md` P0-1,匿名曾可读全站订单)。`ChatToolsStructureTest#noToolExposesUserIdParam()` 用反射锁死这条红线。
- **位图三脚本语义不可混用**:`release_seat.lua` 只清"未售出的锁定位"(待支付单取消/超时用)· `confirm_seat.lua` 置 sold 并同步清 lock · **`refund_seat.lua` 同时清 sold+lock**(退票专用)。退票误用 `releaseSeats` 会导致座位 sold 残留、永久不可再售(P1-1)。改任一脚本后请跑一次"锁座→支付→退票→重新锁座"位图解码回归。
- **`seatIndexes` 必须按场次校验范围, 上界不能用常量** — `LockSeatsDTO` 的 `@Max(4095)` 只是兜底, 真实上界是场次的 `seatCount`(1 号厅 96 / 2 号厅 140), 由 `OrderCore#requireSeatsInRange` 在进 Lua 之前拦住。**越界索引不会被业务拒绝, 而是直接落进 `lock_seat.lua` 的 `SETBIT`** —— Redis bitmap 按 offset 立即分配且永不回收(实测 offset=100000 把位图从 18B 撑到 12501B, 公开的 `GET /seat-map` 响应放大 61 倍; 硬上界 2^32-1 时单 key 512MiB)。`SessionBitmapReader.readWithPadding` 另有读侧截断兜底历史脏位图。改动这条链路请同时跑 `OrderCoreSeatRangeTest` + `LockSeatsDTOValidationTest` + `test/stability_edge_probe.py`。
- **`_denied` / `test/` 下的报告文件**:E2E 报告见 `test/e2e-report-2026-09-29.md`(2026-09-29 的 14 个 bug 已全部修复,`git log` 可查对应 commit)。
- Auth state lives in `localStorage` under `cinema_token` / `cinema_user`; the axios interceptor (`src/api/request.ts`) reads the token and, on `40101/40102`, clears both keys and redirects to `/login`.
- Backend API responses wrap as `{ code, msg, data }`; `code === 0` is success. Distinct codes: `40002` seat-conflict (conflicting seats in `data`), `40900` idempotent conflict, `42900` rate-limited, `40101/40102` auth. Frontend axios unwraps `data` on success.
- WebSocket paths: `/ws/seat/{sessionId}` (public, auto-reconnect + 15s heartbeat in `src/utils/ws.ts`, intentionally hits port 8080 directly, not the vite proxy) and `/ws/admin` (demo-public).
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

Design spec: `docs/superpowers/specs/2026-09-21-chat-assistant-design.md`. The assistant is **read-only + advisory by design** — it returns 「行动卡片」 suggestions and never locks seats, creates orders, or pays (ADR-0002). It rides LangChain4j inside `cinema-server` (ADR-0001) and deliberately does not use RAG (ADR-0003). There are **8 `@Tool` methods** on `ChatTools` (4 movie/session + 2 seat + 2 order); `ChatToolsStructureTest` is the reflection whitelist that fails if any write method ever gets annotated.

### 中英文双语 (i18n)

Design spec: `docs/superpowers/specs/2026-09-22-i18n-zh-en-design.md`. vue-i18n 11 with `legacy: false`, two dictionaries (`zh-CN` / `en-US`), browser-language detection on first visit, localStorage persistence afterwards (user choice beats browser language), `fallbackLocale: 'zh-CN'`.

**`tests/i18n/key-coverage.test.ts` is the safety net for missing keys** — it statically scans `t('...')` usages and diffs both dictionaries, so it catches the case where a key is missing from *both* locales (which `fallbackLocale` cannot rescue; the page would render the raw key). **Run `pnpm test` after touching any locale file or adding a `t()` call.** Also update both `locales/*.ts` together — `locales-shape.test.ts` enforces structural parity.

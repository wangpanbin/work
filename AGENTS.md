# AGENTS.md

Course-design project: cinema ticket flash-sale + seat-selection. Two collaborating modules in this repo:

- `cinema-server/` — Spring Boot 3.5 backend (JDK 21, MyBatis-Plus, Redis 8 + Lua, WebSocket), port **8080**
- `cinema-web/` — Vue 3 + Vite + TS + Element Plus + Pinia frontend, port **5173**

## Running locally

The frontend is useless on its own. `pnpm dev` proxies `/api` and `/ws` to `http://localhost:8080`, so the backend + MySQL + Redis must be up first.

1. Init DB (one-time): `mysql -uroot -p < sql/01_schema.sql`, `02_init_data.sql`, `03_p0_increment.sql` (in repo root `sql/`)
2. Backend: `cd cinema-server && copy src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml` then edit in MySQL password. File is gitignored — do not commit it. Start with `mvn spring-boot:run`.
3. Frontend: `cd cinema-web && pnpm install && pnpm dev` → http://localhost:5173
4. **对话助手(可选)**: 设 `DEEPSEEK_API_KEY` 环境变量 → 后端启动时 `cinema.chat.api-key` 从 `${DEEPSEEK_API_KEY:}` 解析(`application.yml` 已配)。未设时 `/api/chat/message` 返 `50000` + "对话功能未配置"(这是设计:不挂外部 key 也能让 server 起来);设了之后接通 LLM,前端 ChatWidget 可用。USER 级 setx 即可,cmd/PowerShell 需重启继承。

Local middleware: MySQL 8 (local), Redis 8 at **127.0.0.1:6379**. No Docker/RabbitMQ — the delayed-close uses a Redis ZSet delay queue.

Test accounts seeded by backend on startup: `user1/123456`, `user2/123456`, `admin/123456`.

## Commands

- Frontend: `pnpm dev` · `pnpm build` · `pnpm type-check` (`vue-tsc --noEmit`) · `pnpm test` (vitest 3.x, `tests/**/*.test.ts`,**137 用例跨 14 个 `.test.ts` 文件**,2026-09-29 实测). First test file: `tests/views/order/constants.test.ts` (**16 用例**覆盖 ORDER_STATUS 5 态 + 跨态 round-trip). `tests/utils/markdown.test.ts` 是 P2-2 聊天 Markdown 渲染的 24 用例,其中一组专门锁 XSS(先转义后拼标签).
- Backend: `mvn spring-boot:run` · `mvn test` (**139 JUnit5+Mockito unit tests across 26 classes**,2026-09-29 实测;早期数字 29/8 → 69/17 → 105/24 → 118/24 → 115/25 → 127/26 过期).
- There is no linter configured in either module. **build 要单独验**:`pnpm build` 与 `pnpm type-check` 查出的问题不重叠(SCSS 变量/模板编译错误只有 build 报)。
- **数字以实测为准**:README/AGENTS 里的用例数会随测试增长漂移,别照抄文档 —— 跑一次 `mvn test`(或聚合 `target/surefire-reports/TEST-*.xml`)和 `pnpm test` 取真值再更新。

## Conventions & gotchas

- **pnpm v11 blocks dependency postinstall scripts** by default; `pnpm-workspace.yaml` whitelists `esbuild` and `vue-demi` via `allowBuilds`. If installing/skipping builds behaves oddly, this is why.
- Management API requires `role === 1`; the frontend route guard in `cinema-web/src/router/index.ts` blocks non-admins and redirects to `/` with `? _denied=1`. **`_denied` 的唯一消费者是 `App.vue` 的 watcher**(弹 `app.adminDenied` 提示后用 `router.replace` 把参数摘掉)——删掉那个 watcher 就会退回"静默弹回首页"。
- **对话助手的身份参数一律服务端注入**:`ChatTools` 的任何 `@Tool` 方法都**不允许**出现 `userId` 形参,身份取自 `UserContext`。`@P` 参数由 LLM 合成,交给模型填 userId 就是 IDOR 越权(见 `test/e2e-report-2026-09-29.md` P0-1,匿名曾可读全站订单)。`ChatToolsStructureTest#noToolExposesUserIdParam()` 用反射锁死这条红线。
- **位图三脚本语义不可混用**:`release_seat.lua` 只清"未售出的锁定位"(待支付单取消/超时用)· `confirm_seat.lua` 置 sold 并同步清 lock · **`refund_seat.lua` 同时清 sold+lock**(退票专用)。退票误用 `releaseSeats` 会导致座位 sold 残留、永久不可再售(P1-1)。改任一脚本后请跑一次"锁座→支付→退票→重新锁座"位图解码回归。
- **`_denied` / `test/` 下的报告文件**:E2E 报告见 `test/e2e-report-2026-09-29.md`(2026-09-29 的 14 个 bug 已全部修复,`git log` 可查对应 commit)。
- Auth state lives in `localStorage` under `cinema_token` / `cinema_user`; the axios interceptor (`src/api/request.ts`) reads the token and, on `40101/40102`, clears both keys and redirects to `/login`.
- Backend API responses wrap as `{ code, msg, data }`; `code === 0` is success. Distinct codes: `40002` seat-conflict (conflicting seats in `data`), `40900` idempotent conflict, `42900` rate-limited, `40101/40102` auth. Frontend axios unwraps `data` on success.
- WebSocket paths: `/ws/seat/{sessionId}` (public, auto-reconnect + 15s heartbeat in `src/utils/ws.ts`, intentionally hits port 8080 directly, not the vite proxy) and `/ws/admin` (demo-public).
- Seat locking/paying/cancelling are decorated with `@RateLimit` + `@Idempotent` on the server; the frontend must handle the resulting error codes (e.g., refresh seat map on `40002`).
- Business/architecture details live in `docs/实现方案.md` and `docs/superpowers/specs/`. Root `README.md` has the full API table, directory map, and verified end-to-end behavior.
- `test/`, root `test_*.py`, `_report*`, logs, and `application-dev.yml` are gitignored local artifacts — not source of truth.
- E2E/browser work is done via the `playwright-cli` skill.

## Architecture orientation

Frontend (all under `cinema-web/src/`): `api/` axios modules · `router/index.ts` global guards · `stores/` Pinia (user/seat/movieCache) · `utils/ws.ts` seat WebSocket · `utils/bitmap.ts` seat bitmap parsing · `views/` pages plus `views/admin/` management + dashboard/live. Seat selection is the core flash-sale flow (`views/SeatSelect.vue` + `stores/seat.ts`).

## Agent skills

### Issue tracker

GitHub Issues (via the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout. See `docs/agents/domain.md`. `CONTEXT.md` currently covers the **revenue-export** vocabulary plus the **seat-selection** terms (座位索引 / 选座 / 锁座 / 锁座冲突 / 待支付订单 / 场次上下文 / 行动卡片). Decisions live in `docs/adr/`.

### 对话式订票助手 (chat assistant)

Design spec: `docs/superpowers/specs/2026-09-21-chat-assistant-design.md`. The assistant is **read-only + advisory by design** — it returns 「行动卡片」 suggestions and never locks seats, creates orders, or pays (ADR-0002). It rides LangChain4j inside `cinema-server` (ADR-0001) and deliberately does not use RAG (ADR-0003).

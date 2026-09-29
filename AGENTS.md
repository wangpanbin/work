# AGENTS.md

Course-design project: cinema ticket flash-sale + seat-selection. Two collaborating modules in this repo:

- `cinema-server/` — Spring Boot 3.5 backend (JDK 21, MyBatis-Plus, Redis 8 + Lua, WebSocket), port **8080**
- `cinema-web/` — Vue 3 + Vite + TS + Element Plus + Pinia frontend, port **5173**

## Running locally

The frontend is useless on its own. `pnpm dev` proxies `/api` and `/ws` to `http://localhost:8080`, so the backend + MySQL + Redis must be up first.

1. Init DB (one-time): `mysql -uroot -p < sql/01_schema.sql`, `02_init_data.sql`, `03_p0_increment.sql` (in repo root `sql/`). Optional: `04_extra_demo_data.sql` (12 more demo movies + sessions; needs `--default-character-set=utf8mb4`), `06_qa_knowledge.sql` (FAQ knowledge base for the chat assistant — **the table won't exist and `searchFaq` degrades until you run it and restart**).
2. Backend: `cd cinema-server && copy src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml` then edit in MySQL password. File is gitignored — do not commit it. Start with `mvn spring-boot:run`.
3. Frontend: `cd cinema-web && pnpm install && pnpm dev` → http://localhost:5173
4. **对话助手(可选)**: 设 `DEEPSEEK_API_KEY` 环境变量 → 后端启动时 `cinema.chat.api-key` 从 `${DEEPSEEK_API_KEY:}` 解析(`application.yml` 已配)。未设时 `/api/chat/message` 返 `50000` + "对话功能未配置"(这是设计:不挂外部 key 也能让 server 起来);设了之后接通 LLM,前端 ChatWidget 可用。USER 级 setx 即可,cmd/PowerShell 需重启继承。

Local middleware: MySQL 8 (local), Redis 8 at **127.0.0.1:6379**. No Docker/RabbitMQ — the delayed-close uses a Redis ZSet delay queue.

Test accounts seeded by backend on startup: `user1/123456`, `user2/123456`, `admin/123456`.

## Commands

- Frontend: `pnpm dev` · `pnpm build` · `pnpm type-check` (`vue-tsc --noEmit`) · `pnpm test` (vitest 3.x, `tests/**/*.test.ts`,**105 用例跨 12 个 `.test.ts` 文件**). First test file: `tests/views/order/constants.test.ts` (**16 用例**覆盖 ORDER_STATUS 5 态 + 跨态 round-trip).
- Backend: `mvn spring-boot:run` · `mvn test` (**127 JUnit5+Mockito unit tests across 26 classes**,2026-09-29 实测;早期数字 29/8 → 69/17 → 105/24 → 118/24 → 115/25 过期).
- There is no linter configured in either module.
- **数字以实测为准**:README/AGENTS 里的用例数会随测试增长漂移,别照抄文档 —— 跑一次 `mvn test`(或聚合 `target/surefire-reports/TEST-*.xml`)和 `pnpm test` 取真值再更新。

## Conventions & gotchas

- **pnpm v11 blocks dependency postinstall scripts** by default; `pnpm-workspace.yaml` whitelists `esbuild` and `vue-demi` via `allowBuilds`. If installing/skipping builds behaves oddly, this is why.
- Management API requires `role === 1`; the frontend route guard in `cinema-web/src/router/index.ts` blocks non-admins and redirects to `/` with `? _denied=1`.
- Auth state lives in `localStorage` under `cinema_token` / `cinema_user`; the axios interceptor (`src/api/request.ts`) reads the token and, on `40101/40102`, clears both keys and redirects to `/login`.
- Backend API responses wrap as `{ code, msg, data }`; `code === 0` is success. Distinct codes: `40002` seat-conflict (conflicting seats in `data`), `40900` idempotent conflict, `42900` rate-limited, `40101/40102` auth. Frontend axios unwraps `data` on success.
- WebSocket paths: `/ws/seat/{sessionId}` (public, auto-reconnect + 15s heartbeat in `src/utils/ws.ts`, intentionally hits port 8080 directly, not the vite proxy) and `/ws/admin` (demo-public).
- Seat locking/paying/cancelling are decorated with `@RateLimit` + `@Idempotent` on the server; the frontend must handle the resulting error codes (e.g., refresh seat map on `40002`).
- **Vite dev server binds IPv6 `[::1]` only** — probing `http://127.0.0.1:5173` returns connection-refused and looks like the frontend is down when it is actually fine. Use `http://localhost:5173`. `Test-NetConnection -Port 5173` can also report False while the server is up.
- **Session listing filters by date, defaulting to today** — `GET /api/movies/{movieId}/sessions` without `?date=` returns an **empty array** unless sessions exist today. The seeded sessions are a fixed window (`sql/04_extra_demo_data.sql` generates one, e.g. 2026-09-08~09-21) that does **not** roll forward, so an old database looks empty days later. Pass `?date=YYYY-MM-DD` explicitly or re-run the seed script. This is data, not a broken endpoint.
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

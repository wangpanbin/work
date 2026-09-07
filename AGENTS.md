# AGENTS.md

Course-design project: cinema ticket flash-sale + seat-selection. Two collaborating modules in this repo:

- `cinema-server/` — Spring Boot 3.5 backend (JDK 21, MyBatis-Plus, Redis 8 + Lua, WebSocket), port **8080**
- `cinema-web/` — Vue 3 + Vite + TS + Element Plus + Pinia frontend, port **5173**

## Running locally

The frontend is useless on its own. `pnpm dev` proxies `/api` and `/ws` to `http://localhost:8080`, so the backend + MySQL + Redis must be up first.

1. Init DB (one-time): `mysql -uroot -p < sql/01_schema.sql`, `02_init_data.sql`, `03_p0_increment.sql` (in repo root `sql/`)
2. Backend: `cd cinema-server && copy src/main/resources/application-dev.yml.example src/main/resources/application-dev.yml` then edit in MySQL password. File is gitignored — do not commit it. Start with `mvn spring-boot:run`.
3. Frontend: `cd cinema-web && pnpm install && pnpm dev` → http://localhost:5173

Local middleware: MySQL 8 (local), Redis 8 at **127.0.0.1:6379**. No Docker/RabbitMQ — the delayed-close uses a Redis ZSet delay queue.

Test accounts seeded by backend on startup: `user1/123456`, `user2/123456`, `admin/123456`.

## Commands

- Frontend: `pnpm dev` · `pnpm build` · `pnpm type-check` (`vue-tsc --noEmit`). **No test script exists** for the frontend — verify via `pnpm type-check`.
- Backend: `mvn spring-boot:run` · `mvn test` (22 JUnit5+Mockito unit tests).
- There is no linter configured in either module.

## Conventions & gotchas

- **pnpm v11 blocks dependency postinstall scripts** by default; `pnpm-workspace.yaml` whitelists `esbuild` and `vue-demi` via `allowBuilds`. If installing/skipping builds behaves oddly, this is why.
- Management API requires `role === 1`; the frontend route guard in `cinema-web/src/router/index.ts` blocks non-admins and redirects to `/` with `? _denied=1`.
- Auth state lives in `localStorage` under `cinema_token` / `cinema_user`; the axios interceptor (`src/api/request.ts`) reads the token and, on `40101/40102`, clears both keys and redirects to `/login`.
- Backend API responses wrap as `{ code, msg, data }`; `code === 0` is success. Distinct codes: `40002` seat-conflict (conflicting seats in `data`), `40900` idempotent conflict, `42900` rate-limited, `40101/40102` auth. Frontend axios unwraps `data` on success.
- WebSocket paths: `/ws/seat/{sessionId}` (public, auto-reconnect + 15s heartbeat in `src/utils/ws.ts`, intentionally hits port 8080 directly, not the vite proxy) and `/ws/admin` (demo-public).
- Seat locking/paying/cancelling are decorated with `@RateLimit` + `@Idempotent` on the server; the frontend must handle the resulting error codes (e.g., refresh seat map on `40002`).
- Business/architecture details live in `docs/实现方案.md` and `docs/superpowers/specs/`. Root `README.md` has the full API table, directory map, and verified end-to-end behavior.
- `test/`, root `test_*.py`, `_report*`, logs, and `application-dev.yml` are gitignored local artifacts — not source of truth.
- E2E/browser work is done via the `playwright-cli` skill.

## Architecture orientation

Frontend (all under `cinema-web/src/`): `api/` axios modules · `router/index.ts` global guards · `stores/` Pinia (user/seat/movieCache) · `utils/ws.ts` seat WebSocket · `utils/bitmap.ts` seat bitmap parsing · `views/` pages plus `views/admin/` management + dashboard/live. Seat selection is the core flash-sale flow (`views/SeatSelect.vue` + `stores/seat.ts`).

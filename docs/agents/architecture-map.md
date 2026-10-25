# Architecture orientation

前端目录地图与核心文件位置。

> **硬约束仍在 `AGENTS.md`** —— 本文件是专题展开, 不覆盖仓库级底线。
> 改动前先读 `AGENTS.md` 的 `Conventions & gotchas` 一节。

Frontend (all under `cinema-web/src/`): `api/` axios modules · `router/index.ts` global guards · `stores/` Pinia (user/seat/movieCache/**theme**) · `utils/ws.ts` seat WebSocket · `utils/bitmap.ts` seat bitmap parsing · `i18n/` vue-i18n 11 instance (`index.ts` createI18n legacy:false, `detect.ts`, `persist.ts`, `fallback.ts`, `locales/{zh-CN,en-US}.ts`) · **`theme/` 双主题模块**(`resolve.ts` 纯函数 / `apply.ts` 写 DOM / `favicon.ts` 两套图标 / `index.ts` 运行时) · `composables/useChatContext.ts` · `constants/auth.ts` · `components/chat/` (ChatWidget, ChatMessage, ActionCard) · `views/` pages plus `views/admin/` management + dashboard/live. Seat selection is the core flash-sale flow (`views/SeatSelect.vue` + `stores/seat.ts`).

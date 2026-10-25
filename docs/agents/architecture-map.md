# Architecture orientation

前端目录地图与核心文件位置。

> **硬约束仍在 `AGENTS.md`** —— 本文件是专题展开, 不覆盖仓库级底线。
> 改动前先读 `AGENTS.md` 的 `Conventions & gotchas` 一节。

Frontend (all under `cinema-web/src/`): `api/` axios modules · `router/index.ts` global guards · `stores/` Pinia (user/seat/movieCache/**theme**) · `utils/ws.ts` seat WebSocket · `utils/bitmap.ts` seat bitmap parsing · `i18n/` vue-i18n 11 instance (`index.ts` createI18n legacy:false, `detect.ts`, `persist.ts`, `fallback.ts`, `locales/{zh-CN,en-US}.ts`) · **`theme/` 双主题模块**(`resolve.ts` 纯函数 / `apply.ts` 写 DOM / `favicon.ts` 两套图标 / `index.ts` 运行时) · `composables/` (`useChatContext.ts` · **`useSeatSession.ts` 选座页「装载一个场次」的编排** —— 依赖注入,node 环境可单测;`useSeatSync.ts` 权威重拉调度) · `utils/` 里的业务纯函数(`hallForm.ts` 影厅表单往返 · `adminForm.ts` 管理端表单校验/错误文案 · `loginRequiredDetector.ts` 聊天「请登录」识别) · `constants/auth.ts` · `components/chat/` (ChatWidget, ChatMessage, ActionCard) · `views/` pages plus `views/admin/` management + dashboard/live (`views/admin/breadcrumb.ts` 面包屑映射纯函数). Seat selection is the core flash-sale flow (`views/SeatSelect.vue` + `stores/seat.ts`).

测试侧的公共 helper 在 `cinema-web/tests/helpers/source.ts`(`code()` 先剥注释再断言 · `fnBody()` 按顶层声明边界切函数体)—— **静态契约断言必须用它**,固定字符窗口会串到隔壁函数、断言变恒真。

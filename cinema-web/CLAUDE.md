# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

`cinema-web/` 是影院抢票系统的前端模块,对应后端 `../cinema-server/`(Spring Boot 3.5,端口 8080)。前端本身不能独立运行 —— `pnpm dev` 起的 Vite 通过 `/api` 与 `/ws` 代理把请求转发到 `http://localhost:8080`,所以后端 + MySQL + Redis 必须在前面起来。完整跨模块指引(初始化顺序、测试账号、API 表、压测结果)见 `../README.md` 与 `../AGENTS.md`。

## 常用命令

- `pnpm dev` — 启动 Vite 开发服,5173 端口
- `pnpm build` — 生产构建到 `dist/`
- `pnpm preview` — 预览生产构建
- `pnpm type-check` — `vue-tsc --noEmit`,作为改动后唯一的静态校验入口

**没有 linter,没有前端单元测试脚本**。任何改动以 `pnpm type-check` 通过为准;行为验证靠人工跑 `pnpm dev` 或浏览器 E2E(用 `playwright-cli` 技能)。E2E 脚本 `../test_webapp_e2e.py` 与产物 `_report*` 都已被 `.gitignore` 排除,不进版本库。

## 架构概览

```
src/
├── main.ts                # createApp + Pinia + Router + ElementPlus
├── App.vue                # 全局壳: 顶部品牌/用户区(桌面 chip + 移动 dropdown), 退出登录二次确认
├── api/                   # 按领域拆分的 axios 模块: user/movie/session/seat/order/admin
│   └── request.ts         # axios 实例 + 拦截器(注入 JWT + 业务错误码处理)
├── router/index.ts        # createWebHistory + 全局 beforeEach 守卫
├── stores/                # Pinia stores
│   ├── user.ts            # 同步从 localStorage 初始化(守卫需要), login/logout/fetchMe
│   ├── seat.ts            # 座位图核心 store: 位图 + O(1) 查表 + 选中/冲突闪烁
│   └── movieCache.ts      # 5 分钟 TTL 路由级缓存(Home/MovieDetail 命中,Admin CRUD 不主动失效)
├── utils/
│   ├── ws.ts              # 座位 WebSocket 客户端(自动重连 + 指数退避 + 15s 心跳 + 状态 ref)
│   └── bitmap.ts          # 后端 Base64 Big-Endianian 位图 → Uint8Array(每位 = 一个座位)
├── components/            # SeatItem(单座状态), Countdown(下单/支付倒计时)
├── views/                 # 页面: Home / Login / MovieDetail / SeatSelect / OrderList / Payment / NotFound
│   └── admin/             # AdminHome(嵌套壳) + Dashboard / LiveDashboard / MovieManage / HallManage / SessionManage
├── types/                 # TS 类型: R<T> 业务响应, UserVO/Movie/SessionVO/PageData
└── styles/main.css        # 全局变量(主题色/字体/渐变) + 响应式
```

### 抢票主链路(SeatSelect)

这是整个项目的核心交互,改这块前必须先理解:

1. `views/SeatSelect.vue` 拉一次座位图(GET `/sessions/{id}/seat-map`) → 写入 `stores/seat.ts.load()`
2. 同时 `utils/ws.ts#createSeatWs(sessionId, onEvent)` 建立 WebSocket(`ws://host:8080/ws/seat/{id}`,**绕过 Vite 代理直连 8080**)
3. 事件 `LOCKED` / `SOLD` / `RELEASED` 由 `seat.ts#applyEvent` 应用到本地位图;`LOCKED`/`SOLD` 还会把原本在 `selected` 里的座位加入 `conflictFlash`,1.8s 后清除(驱动 UI 闪烁提示"刚被他人抢走")
4. 用户点击空座 → `toggle(idx)`(上限 4);提交 → `POST /api/orders/lock`(后端走 Lua 原子锁座 + `@RateLimit` + `@Idempotent`)
5. 成功后跳转 `/payment`,15min 待支付窗口;`Countdown` 倒计时;支付完成去 `/orders`

## 后端响应与错误码约定

`../AGENTS.md` 已经写过,这里只列前端必须会处理的几个:

- 统一信封 `{ code, msg, data }`,`code === 0` 视为成功,`request.ts` 拦截器直接返回 `body.data`
- **40002** 锁座冲突 → `error.data.conflict` 是冲突座位 index 列表,前端要刷新座位图并提示
- **40900** 幂等冲突(短时间内重复提交同一锁座/支付/退票)
- **42900** 限流(后端 Redis 滑动窗口,100 次同用户锁座 → 第 6 次起命中)
- **40101 / 40102** 登录态失效 → 拦截器清掉 `cinema_token` + `cinema_user` 并跳 `/login?redirect=当前路径`

## 关键约定与坑

- **pnpm v11 默认拦截 postinstall**;`pnpm-workspace.yaml` 已白名单 `esbuild` 与 `vue-demi`。安装出问题先看这里,不要随意加 `--ignore-scripts`
- **雪花 ID 一律 string**:`SessionVO.id`/`SeatMap.sessionId`/`Movie.id` 在 URL/缓存 key 里都用字符串,避免 JS Number 精度截断(超过 2^53)
- **JWT 存储**:`localStorage.cinema_token`(token)和 `localStorage.cinema_user`(用户缓存)。`stores/user.ts` 同步读 `cinema_user` 才能让 `router.beforeEach` 在首次导航就拿到 `role`,否则守卫判错
- **管理端权限**:`user.role === 1` 才放行 `/admin/*`;非管理员被守卫跳到 `/?_denied=1`
- **axios 拦截器不在组件上下文**,路由守卫的非管理员拦截也不要弹 `ElMessage` —— 提示逻辑放 `App.vue` 监听
- **WebSocket 故意绕过 Vite 代理**:`utils/ws.ts` 里端口判定 `location.port === '5173' ? '8080' : location.port`,所以 HMR 代理不会进 WS 流。心跳已经压到 15s(防部分网关 30s idle 切断)
- **网络错误的本地化文案**:`request.ts` 把 `Network Error` 替换成"网络异常,请确认后端已启动(8080)",别改这个文案,方便本地排障
- **路由级缓存的失效边界**:`movieCache.ts` 不监听 Admin CRUD,所以管理员改完影片/场次,用户端最长 5 分钟内可能看到陈旧数据 —— 与后端方案文档一致,不主动补救
- **404 兜底**:`router` 末尾的 `/:pathMatch(.*)*` 指向 `NotFound.vue`,外部死链进来不会白屏
/**
 * 路由常量 —— 路径与路由名的唯一归属。
 *
 * <p>**为什么要有它**: 路由路径此前散在 `router/index.ts` 的路由表、路由守卫,
 * 以及 `App.vue` / `Payment.vue` / `MovieDetail.vue` / `SeatSelect.vue` /
 * `OrderList.vue` / `admin/AdminHome.vue` / `admin/Dashboard.vue` / `NotFound.vue`
 * / `api/request.ts` / `components/chat/ChatWidget.vue` 等十来个地方的
 * `router.push('/xxx')` 字面量。改一条路径要全仓扫字符串, 漏一个就 404。
 *
 * <p>本模块只放**字面量**, 不含逻辑。守卫的「哪些路径要登录 / 要管理员」
 * 也在这里, 保证判定口径与路由表同源。
 */

/**
 * 路由路径 —— 参数用 `:id` 风格, 与 vue-router 路由表一致。
 *
 * <p>⚠️ **`home` 的语义在 2026-10-26 变过**: 根路径 `/` 现在是**产品主页**
 * (`views/Landing.vue`), 影片浏览页 (`views/Home.vue`) 迁到了 `/movies`。
 * 旧的 `/landing` 保留为**重定向别名** —— 书签与外链不会 404。
 */
export const ROUTE_PATHS = {
  home: '/',
  movies: '/movies',
  landing: '/landing',
  login: '/login',
  movieDetail: '/movie/:id',
  seatSelect: '/seat/:sessionId',
  payment: '/payment',
  orderList: '/orders',
  adminHome: '/admin',
  adminDashboard: '/admin/dashboard',
  adminLive: '/admin/live',
  adminMovies: '/admin/movies',
  adminHalls: '/admin/halls',
  adminSessions: '/admin/sessions',
  adminChatStats: '/admin/chat-stats',
} as const

/** 路由名 —— `router.push({ name: ... })` 用。 */
export const ROUTE_NAMES = {
  home: 'home',
  movies: 'movies',
  landing: 'landing',
  login: 'login',
  movieDetail: 'movie-detail',
  seatSelect: 'seat-select',
  payment: 'payment',
  orderList: 'order-list',
  adminHome: 'admin-home',
  adminDashboard: 'admin-dashboard',
  adminLive: 'admin-live',
  adminMovies: 'admin-movies',
  adminHalls: 'admin-halls',
  adminSessions: 'admin-sessions',
  adminChatStats: 'admin-chat-stats',
} as const

/**
 * 需要登录的路径 —— 精确匹配。
 *
 * <p>与「前缀匹配」的分开是因为口径不同: `/seat` 下还有子路径,
 * 而 `/payment` 与 `/orders` 是整段路径。
 */
export const AUTH_REQUIRED_PATHS: readonly string[] = [ROUTE_PATHS.payment, ROUTE_PATHS.orderList]

/** 需要登录的路径前缀。 */
export const AUTH_REQUIRED_PREFIXES: readonly string[] = ['/seat']

/** 需要管理员的路径前缀。 */
export const ADMIN_REQUIRED_PREFIX = '/admin'

/** 管理端根路径 —— 用于「从管理端跳回首页」。 */
export const ADMIN_HOME_PATH = ROUTE_PATHS.adminHome

/**
 * 拼座位页路径 —— 场次 id 在 URL 里。
 *
 * <p>id 可能是 number 或 string(后端 wire 类型多态, 见 `types/index.ts` 的 `Id`),
 * 这里统一走 `String()` 转换, 避免出现 `seat/123` 与 `seat/123` 之外的怪形态。
 */
export function seatPath(sessionId: string | number): string {
  return `/seat/${sessionId}`
}

/** 拼影片页路径。 */
export function moviePath(movieId: string | number): string {
  return `/movie/${movieId}`
}

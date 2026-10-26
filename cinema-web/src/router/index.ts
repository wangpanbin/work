import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'
import {
  ADMIN_REQUIRED_PREFIX,
  ROUTE_NAMES,
  ROUTE_PATHS,
} from '../constants/routes'
import { shouldRedirectToLogin } from '../utils/authRedirect'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 产品主页(**默认入口**, 公开): 版式来自一次性分支 prototype/landing-variants
    // 的变体挑选(A hero + C bento)。2026-10-26 起根路径归它 —— 打开站点先看到门面,
    // 登录入口在顶栏与 hero 区, 不把人拦在登录页。
    { path: ROUTE_PATHS.home, name: ROUTE_NAMES.home, component: () => import('../views/Landing.vue') },
    // 影片浏览页(公开): 原 `Home.vue`, 从根路径迁到这里。
    { path: ROUTE_PATHS.movies, name: ROUTE_NAMES.movies, component: () => import('../views/Home.vue') },
    // 旧地址(`/landing`)保留为重定向别名 —— 书签与外链不该 404。
    { path: ROUTE_PATHS.landing, name: ROUTE_NAMES.landing, redirect: ROUTE_PATHS.home },
    { path: ROUTE_PATHS.login, name: ROUTE_NAMES.login, component: () => import('../views/Login.vue') },
    { path: ROUTE_PATHS.movieDetail, name: ROUTE_NAMES.movieDetail, component: () => import('../views/MovieDetail.vue') },
    { path: ROUTE_PATHS.seatSelect, name: ROUTE_NAMES.seatSelect, component: () => import('../views/SeatSelect.vue') },
    { path: ROUTE_PATHS.payment, name: ROUTE_NAMES.payment, component: () => import('../views/Payment.vue') },
    { path: ROUTE_PATHS.orderList, name: ROUTE_NAMES.orderList, component: () => import('../views/OrderList.vue') },
    { path: ROUTE_PATHS.adminHome, name: ROUTE_NAMES.adminHome, component: () => import('../views/admin/AdminHome.vue'),
      children: [
        { path: 'dashboard', name: 'admin-dashboard', component: () => import('../views/admin/Dashboard.vue') },
        { path: 'live', name: 'admin-live', component: () => import('../views/admin/LiveDashboard.vue') },
        { path: 'movies', name: 'admin-movies', component: () => import('../views/admin/MovieManage.vue') },
        { path: 'halls', name: 'admin-halls', component: () => import('../views/admin/HallManage.vue') },
        { path: 'sessions', name: 'admin-sessions', component: () => import('../views/admin/SessionManage.vue') },
        { path: 'chat-stats', name: 'admin-chat-stats', component: () => import('../views/admin/ChatStats.vue') },
      ]
    },
    // 兜底 404, 避免外部死链进来白屏
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../views/NotFound.vue') },
  ],
})

/**
 * 全局路由守卫 (P0 修复):
 * - /seat/* /payment /orders: 必须登录, 未登录跳 /login 带 redirect
 * - /admin/*: 必须 role === 1, 否则跳 / 并提示
 * - 守卫依赖 userStore.user, 故 user 必须能从 localStorage 同步初始化
 *   (见 stores/user.ts readCachedUser)
 *
 * <p>「哪些路径需要登录」只有一份判定 —— `utils/authRedirect.ts` 的
 * `shouldRedirectToLogin`, axios 拦截器也用它。两边各写一遍迟早漂移。
 */
router.beforeEach((to, from, next) => {
  const userStore = useUserStore()

  // 已登录用户访问 /login 直接跳首页
  if (to.name === ROUTE_NAMES.login && userStore.isLogin) {
    return next(ROUTE_PATHS.home)
  }

  const needAuth = shouldRedirectToLogin(to.path)
  if (needAuth && !userStore.isLogin) {
    return next({ path: ROUTE_PATHS.login, query: { redirect: to.fullPath } })
  }

  const needAdmin = to.path.startsWith(ADMIN_REQUIRED_PREFIX)
  if (needAdmin && userStore.user?.role !== 1) {
    // 非管理员: 拦截到首页, 不渲染后台骨架
    // 不弹 ElMessage (这里不在组件上下文, 也不在 request 拦截器中), 由 App.vue 监听
    return next({ path: ROUTE_PATHS.home, query: { _denied: '1' } })
  }

  next()
})

export default router

import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('../views/Home.vue') },
    { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
    { path: '/movie/:id', name: 'movie-detail', component: () => import('../views/MovieDetail.vue') },
    { path: '/seat/:sessionId', name: 'seat-select', component: () => import('../views/SeatSelect.vue') },
    { path: '/payment', name: 'payment', component: () => import('../views/Payment.vue') },
    { path: '/orders', name: 'order-list', component: () => import('../views/OrderList.vue') },
    { path: '/admin', name: 'admin-home', component: () => import('../views/admin/AdminHome.vue'),
      children: [
        { path: 'dashboard', name: 'admin-dashboard', component: () => import('../views/admin/Dashboard.vue') },
        { path: 'live', name: 'admin-live', component: () => import('../views/admin/LiveDashboard.vue') },
        { path: 'movies', name: 'admin-movies', component: () => import('../views/admin/MovieManage.vue') },
        { path: 'halls', name: 'admin-halls', component: () => import('../views/admin/HallManage.vue') },
        { path: 'sessions', name: 'admin-sessions', component: () => import('../views/admin/SessionManage.vue') },
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
 */
router.beforeEach((to, from, next) => {
  const userStore = useUserStore()

  // 已登录用户访问 /login 直接跳首页
  if (to.name === 'login' && userStore.isLogin) {
    return next('/')
  }

  const needAuth = to.path.startsWith('/seat') || to.path === '/payment' || to.path === '/orders'
  if (needAuth && !userStore.isLogin) {
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  const needAdmin = to.path.startsWith('/admin')
  if (needAdmin && userStore.user?.role !== 1) {
    // 非管理员: 拦截到首页, 不渲染后台骨架
    // 不弹 ElMessage (这里不在组件上下文, 也不在 request 拦截器中), 由 App.vue 监听
    return next({ path: '/', query: { _denied: '1' } })
  }

  next()
})

export default router

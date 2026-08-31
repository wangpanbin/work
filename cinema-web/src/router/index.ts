import { createRouter, createWebHistory } from 'vue-router'

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
  ],
})

export default router

import { ref, computed, onScopeDispose } from 'vue'
import { defineStore } from 'pinia'
import { login as apiLogin, me as apiMe } from '../api/user'
import type { UserVO } from '../types'
import { AUTH_TOKEN_KEY, AUTH_USER_KEY } from '../constants/auth'
import { onAuthSessionInvalidated } from '../utils/authSession'

function readCachedUser(): UserVO | null {
  try {
    const raw = localStorage.getItem(AUTH_USER_KEY)
    return raw ? (JSON.parse(raw) as UserVO) : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  // user 也同步初始化, 让 router.beforeEach 能立刻拿到 role 做权限判断
  // (App.vue onMounted 才调 fetchMe, 守卫时机更早, 异步 user.value 拿不到)
  const token = ref<string>(localStorage.getItem(AUTH_TOKEN_KEY) || '')
  const user = ref<UserVO | null>(readCachedUser())
  const isLogin = computed(() => !!token.value)

  /**
   * 登录态失效广播(2026-10-26)—— axios 拦截器收到 401 时清掉 localStorage 后,
   * 这里把 store 里的 token / user 也置空。
   *
   * <p>不解这一条的后果: store 仍以为用户已登录 → 顶栏照显示「我的订单 /
   * 管理端」, 路由守卫照放行 → 后续请求继续 401 → 死循环,
   * 「静默降级为匿名」就成了假象。
   */
  const unsubscribe = onAuthSessionInvalidated(() => {
    token.value = ''
    user.value = null
  })
  // store 被销毁(HMR / Pinia dispose)时退订 —— 否则模块里的订阅表会攒下死回调。
  onScopeDispose(unsubscribe)

  async function login(username: string, password: string) {
    const data = await apiLogin({ username, password })
    token.value = data.token
    user.value = data.user
    localStorage.setItem(AUTH_TOKEN_KEY, data.token)
    localStorage.setItem(AUTH_USER_KEY, JSON.stringify(data.user))
  }

  async function fetchMe() {
    if (!token.value) return
    try {
      const fresh = await apiMe()
      user.value = fresh
      localStorage.setItem(AUTH_USER_KEY, JSON.stringify(fresh))
    } catch {
      // 401 已由拦截器统一处理
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(AUTH_TOKEN_KEY)
    localStorage.removeItem(AUTH_USER_KEY)
  }

  return { token, user, isLogin, login, fetchMe, logout }
})

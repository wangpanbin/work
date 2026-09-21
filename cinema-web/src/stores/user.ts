import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { login as apiLogin, me as apiMe } from '../api/user'
import type { UserVO } from '../types'
import { AUTH_TOKEN_KEY, AUTH_USER_KEY } from '../constants/auth'

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

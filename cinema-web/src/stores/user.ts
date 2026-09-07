import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { login as apiLogin, me as apiMe } from '../api/user'
import type { UserVO } from '../types'

const TOKEN_KEY = 'cinema_token'
const USER_KEY = 'cinema_user'

function readCachedUser(): UserVO | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as UserVO) : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  // user 也同步初始化, 让 router.beforeEach 能立刻拿到 role 做权限判断
  // (App.vue onMounted 才调 fetchMe, 守卫时机更早, 异步 user.value 拿不到)
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<UserVO | null>(readCachedUser())
  const isLogin = computed(() => !!token.value)

  async function login(username: string, password: string) {
    const data = await apiLogin({ username, password })
    token.value = data.token
    user.value = data.user
    localStorage.setItem(TOKEN_KEY, data.token)
    localStorage.setItem(USER_KEY, JSON.stringify(data.user))
  }

  async function fetchMe() {
    if (!token.value) return
    try {
      const fresh = await apiMe()
      user.value = fresh
      localStorage.setItem(USER_KEY, JSON.stringify(fresh))
    } catch {
      // 401 已由拦截器统一处理
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, isLogin, login, fetchMe, logout }
})

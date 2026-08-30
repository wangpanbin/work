import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { login as apiLogin, me as apiMe } from '../api/user'
import type { UserVO } from '../types'

const TOKEN_KEY = 'cinema_token'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref<UserVO | null>(null)
  const isLogin = computed(() => !!token.value)

  async function login(username: string, password: string) {
    const data = await apiLogin({ username, password })
    token.value = data.token
    user.value = data.user
    localStorage.setItem(TOKEN_KEY, data.token)
  }

  async function fetchMe() {
    if (!token.value) return
    try {
      user.value = await apiMe()
    } catch {
      // 401 已由拦截器统一处理
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return { token, user, isLogin, login, fetchMe, logout }
})

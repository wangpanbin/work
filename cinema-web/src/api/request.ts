import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { i18n } from '../i18n'
import { AUTH_TOKEN_KEY, AUTH_USER_KEY, AUTH_CODES } from '../constants/auth'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(AUTH_TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      if (body.code === AUTH_CODES.UNAUTHORIZED || body.code === AUTH_CODES.TOKEN_EXPIRED) {
        localStorage.removeItem(AUTH_TOKEN_KEY)
        localStorage.removeItem(AUTH_USER_KEY)   // P0-1: 同步清掉 user 缓存, 守卫能立刻判定为未登录
        if (router.currentRoute.value.path !== '/login') {
          // 拦截器不在组件上下文, 用 i18n 单例取文案(zh-CN 值必须与排障约定一致, 见 api.* 注释)
          ElMessage.warning(body.msg || i18n.global.t('api.needLogin'))
          // 带上 redirect, 登录成功后能回到原页面
          router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
        }
        // 40101/40102 也要挂上 code —— 否则上层按 code 分支的逻辑
        // (比如确认卡上的「请先登录后再下单」)永远走不到, 只能退化到 message
        const err = new Error(body.msg || i18n.global.t('api.needLogin')) as Error & { data?: unknown; code?: number }
        err.code = body.code
        err.data = body.data
        return Promise.reject(err)
      }
      // Phase D-⑱: 把 body.data 挂到 error 上, 让上层拿到业务数据(如锁座 conflict 列表)
      const err = new Error(body.msg || i18n.global.t('api.requestFailed')) as Error & { data?: unknown; code?: number }
      err.code = body.code
      err.data = body.data
      ElMessage.error(body.msg || i18n.global.t('api.requestFailed'))
      return Promise.reject(err)
    }
    return body
  },
  (error) => {
    const msg = error.message === 'Network Error' ? i18n.global.t('api.networkError') : error.message || i18n.global.t('api.requestFailed')
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default request

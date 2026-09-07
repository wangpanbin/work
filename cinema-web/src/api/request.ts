import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const TOKEN_KEY = 'cinema_token'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
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
      if (body.code === 40101 || body.code === 40102) {
        localStorage.removeItem(TOKEN_KEY)
        localStorage.removeItem('cinema_user')   // P0-1: 同步清掉 user 缓存, 守卫能立刻判定为未登录
        if (router.currentRoute.value.path !== '/login') {
          ElMessage.warning(body.msg || '请先登录')
          // 带上 redirect, 登录成功后能回到原页面
          router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
        }
        return Promise.reject(new Error(body.msg))
      }
      // Phase D-⑱: 把 body.data 挂到 error 上, 让上层拿到业务数据(如锁座 conflict 列表)
      const err = new Error(body.msg || '请求失败') as Error & { data?: unknown; code?: number }
      err.code = body.code
      err.data = body.data
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(err)
    }
    return body
  },
  (error) => {
    const msg = error.message === 'Network Error' ? '网络异常,请确认后端已启动(8080)' : error.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default request

import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { i18n } from '../i18n'
import { errorText } from '../i18n/errorText'
import { AUTH_TOKEN_KEY, AUTH_CODES } from '../constants/auth'
import { ROUTE_PATHS } from '../constants/routes'
import { invalidateAuthSession } from '../utils/authSession'
import { shouldRedirectToLogin } from '../utils/authRedirect'
import { ApiError } from './errorCodes'

/**
 * axios 请求配置的**模块增强** —— 让全站任何请求都能带这个业务标记。
 *
 * <p>`skipErrorToast`: **业务层自己会弹提示时置 true** —— 拦截器就不再弹通用 toast。
 * 否则同一次失败会弹两条(通用一条 + 业务一条), 而且通用那条是后端的中文原文,
 * 切到英文界面就成了「英文里夹中文」(联调报告 F1)。
 *
 * <p>用法:`request.post('/orders/lock', data, { skipErrorToast: true })`。
 */
declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 业务层自行提示错误时置 true: 拦截器不再弹通用 toast */
    skipErrorToast?: boolean
  }
}

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

/**
 * 通用错误文案。
 *
 * <p>后端消息是中文的 —— 英文界面直接透出会「英文里夹中文」。
 * 中文界面保留后端原文(信息量最大: 「场次已开场」比「请求失败」有用得多);
 * 其它界面退化为本地化通用文案。规则统一在 {@link errorText}。
 */

/** 该次请求是否已声明「业务层自己提示」 */
function skipToast(config: unknown): boolean {
  return (config as { skipErrorToast?: boolean } | undefined)?.skipErrorToast === true
}

request.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data
      }
      if (body.code === AUTH_CODES.UNAUTHORIZED || body.code === AUTH_CODES.TOKEN_EXPIRED) {
        // P0-1 + 2026-10-26: 登录态失效是**全局**事件 —— localStorage 与 Pinia store
        // 一起清(后者靠事件广播, 见 utils/authSession.ts)。只清 localStorage 的话,
        // store 里的 token 还是旧值 → 顶栏照显示已登录、守卫照放行 → 后续请求继续 401。
        invalidateAuthSession()
        // 未登录一律用本地化文案: 后端原文是中文, 英文界面不该夹中文
        const msg = i18n.global.t('api.needLogin')
        const cur = router.currentRoute.value
        // ⚠️ 只有「**当前页本身**需要登录」才把人带去登录页。
        // 公开页(如产品主页 / 影片浏览页)静默降级: 清 token、留在原地、不弹提示 ——
        // 2026-10-26 的真问题就是「一打开就被弹到登录页」, 那是浏览器里存着
        // 过期 token 导致的, 不该让匿名访客为此买单。
        if (shouldRedirectToLogin(cur.path) && cur.path !== ROUTE_PATHS.login) {
          // 拦截器不在组件上下文, 用 i18n 单例取文案(zh-CN 值必须与排障约定一致, 见 api.* 注释)
          ElMessage.warning(msg)
          // 带上 redirect, 登录成功后能回到原页面
          router.push({ path: ROUTE_PATHS.login, query: { redirect: cur.fullPath } })
        }
        // 40101/40102 也要挂上 code —— 否则上层按 code 分支的逻辑
        // (比如确认卡上的「请先登录后再下单」)永远走不到, 只能退化到 message
        const err = new Error(msg) as ApiError
        err.code = body.code
        err.data = body.data
        return Promise.reject(err)
      }
      // Phase D-⑱: 把 body.data 挂到 error 上, 让上层拿到业务数据(如锁座 conflict 列表)
      const err = new Error(body.msg || i18n.global.t('api.requestFailed')) as ApiError
      err.code = body.code
      err.data = body.data
      // 业务层自己弹提示时不再重复弹(联调报告 F1: 一条错误弹两条)
      if (!skipToast(resp.config)) {
        ElMessage.error(errorText(body.msg))
      }
      return Promise.reject(err)
    }
    return body
  },
  (error) => {
    const msg = error.message === 'Network Error' ? i18n.global.t('api.networkError') : error.message || i18n.global.t('api.requestFailed')
    if (!skipToast(error.config)) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  },
)

export default request

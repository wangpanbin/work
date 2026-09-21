/**
 * 鉴权相关常量 — 单一真理之源.
 *
 * <p>#8 收尾: 消除前端散落 4 处的 token key 字面量 + 1 处的 40101/40102 magic int.
 * 与 backend `ResultCode.java`(40101 UNAUTHORIZED / 40102 TOKEN_EXPIRED) 一一对应.
 *
 * <p>修改 AUTH_CODES 时**必须**同步 backend ResultCode; 反之亦然.
 * 这是跨前后端的契约, 任何 drift 都会导致 axios 拦截器不再触发 401 清理.
 */

/** localStorage 中存放 JWT 的 key */
export const AUTH_TOKEN_KEY = 'cinema_token'

/** localStorage 中存放 user JSON 的 key */
export const AUTH_USER_KEY = 'cinema_user'

/**
 * 后端 ResultCode 鉴权相关枚举的前端镜像。
 * UNAUTHORIZED / TOKEN_EXPIRED 触发"清缓存 + 跳 /login"流程。
 */
export const AUTH_CODES = {
  UNAUTHORIZED: 40101,
  TOKEN_EXPIRED: 40102,
} as const

/** 类型化的 auth code, 用于类型收窄的拦截器逻辑 */
export type AuthCode = typeof AUTH_CODES[keyof typeof AUTH_CODES]
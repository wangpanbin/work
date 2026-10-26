import { AUTH_REQUIRED_PATHS, AUTH_REQUIRED_PREFIXES } from '../constants/routes'

/**
 * 这个路径**是否需要登录**?
 *
 * <p>判定口径只有这一份 —— 路由守卫 (`router/index.ts`) 与 axios 响应拦截器
 * (`api/request.ts`) 共用它。两边各写一遍的话, 迟早会漂移:
 * 守卫放行但拦截器跳登录(用户莫名其妙被踢走), 或者反过来
 * (用户停在受保护页上看一个空壳)。
 *
 * <p>⚠️ `/admin*` **不在**这里 —— 管理端是**角色**判定(`role === 1`),
 * 走另一条分支。见 `router/index.ts`。
 *
 * <p>纯函数, 不碰路由实例 —— 便于单测直接调用。
 */
export function shouldRedirectToLogin(path: string): boolean {
  return (
    AUTH_REQUIRED_PATHS.includes(path) ||
    AUTH_REQUIRED_PREFIXES.some((p) => path.startsWith(p))
  )
}

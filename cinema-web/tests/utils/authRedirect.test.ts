import { describe, it, expect } from 'vitest'
import { shouldRedirectToLogin } from '../../src/utils/authRedirect'

/**
 * 「哪些路径需要登录」的判定契约。
 *
 * <p>**为什么值得单独锁**: 这份判定被**两处**消费 ——
 * 路由守卫(`router/index.ts`)决定「进不进得了页面」, axios 拦截器
 * (`api/request.ts`)决定「401 之后把人带去哪」。两边各写一遍的话,
 * 迟早漂移: 守卫放行但拦截器跳登录(用户被莫名其妙踢走), 或者反过来
 * (用户停在受保护页上看一个空壳)。
 *
 * <p>2026-10-26 的真 bug 就是这一类: 拦截器**无条件**跳 `/login`,
 * 于是浏览器里存着过期 token 的访客, 打开产品主页第一眼看到的是登录页。
 */
describe('shouldRedirectToLogin', () => {
  it('受保护路径: 需要登录', () => {
    expect(shouldRedirectToLogin('/payment')).toBe(true)
    expect(shouldRedirectToLogin('/orders')).toBe(true)
    expect(shouldRedirectToLogin('/seat/123')).toBe(true)
  })

  it('公开路径: 不需要登录 —— 匿名访客必须能停在主页上', () => {
    expect(shouldRedirectToLogin('/')).toBe(false)
    expect(shouldRedirectToLogin('/movies')).toBe(false)
    expect(shouldRedirectToLogin('/movie/42')).toBe(false)
    expect(shouldRedirectToLogin('/landing')).toBe(false)
    expect(shouldRedirectToLogin('/login')).toBe(false)
  })

  it('/admin 不在这份判定里 —— 它走角色判定(role === 1)', () => {
    expect(shouldRedirectToLogin('/admin')).toBe(false)
    expect(shouldRedirectToLogin('/admin/movies')).toBe(false)
  })
})

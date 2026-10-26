import { describe, it, expect } from 'vitest'
import { code } from '../helpers/source'

/**
 * 401 分流的静态契约(2026-10-26)。
 *
 * <p>**为什么只能静态扫**: `api/request.ts` 一 import 就拉进 axios /
 * element-plus / vue-router, 本仓 vitest 跑在 `environment: 'node'` 且没装
 * jsdom / @vue/test-utils —— 挂不起组件, 也没法跑拦截器。
 * 所以这里扫源码: 断言「401 分支不再无条件跳登录页」。
 *
 * <p>⚠️ 用 `code()` 先剥注释 —— 本仓注释里大量写着「原先是怎么写的」,
 * 拿原文去断言会永远命中, 断言就成了恒真假绿。
 *
 * <p>**这条契约锁的是 2026-10-26 的真 bug**: 浏览器里存着过期 token 的
 * 访客, 打开站点第一眼看到的是登录页 —— 因为拦截器收到 401 就无条件
 * `router.push('/login')`, 不管当前在哪个页面。
 */
describe('api/request.ts — 401 分流', () => {
  const src = code('../../src/api/request.ts')

  it('401 分支走失效广播(清 localStorage + 通知 store 降级)', () => {
    expect(src).toContain('invalidateAuthSession()')
  })

  it('401 分支按「当前路由是否需要登录」分流, 不再无条件跳登录页', () => {
    expect(src).toContain('shouldRedirectToLogin(')

    // 整个文件里只允许有一处 router.push —— 401 分支那处,
    // 而且它必须被 shouldRedirectToLogin 守着。
    const pushes = [...src.matchAll(/router\.push\(/g)]
    expect(pushes.length, 'request.ts 里不该有第二处 router.push').toBe(1)

    const at = pushes[0].index ?? -1
    const guard = src.slice(Math.max(0, at - 400), at)
    expect(
      guard,
      'router.push 没有被 shouldRedirectToLogin 守着 —— 公开页也会被弹到登录页',
    ).toContain('shouldRedirectToLogin(')
  })

  it('跳登录页时带 redirect, 登录后能回原页面', () => {
    expect(src).toContain("redirect: cur.fullPath")
  })

  it('401 仍然挂上 code 与 data —— 上层按 err.code 分支', () => {
    expect(src).toContain('err.code = body.code')
    expect(src).toContain('err.data = body.data')
  })
})

describe('router/index.ts — 守卫与拦截器同源', () => {
  const src = code('../../src/router/index.ts')

  it('守卫用共享的 shouldRedirectToLogin, 不自己再写一遍口径', () => {
    expect(src).toContain('shouldRedirectToLogin(to.path)')
    expect(src).not.toContain('AUTH_REQUIRED_PATHS')
    expect(src).not.toContain('AUTH_REQUIRED_PREFIXES')
  })

  it('根路径是产品主页, 影片浏览页在 /movies, 旧 /landing 是重定向', () => {
    expect(src).toMatch(/ROUTE_PATHS\.home,\s*name:\s*ROUTE_NAMES\.home,\s*component:\s*\(\)\s*=>\s*import\('\.\.\/views\/Landing\.vue'\)/)
    expect(src).toMatch(/ROUTE_PATHS\.movies,\s*name:\s*ROUTE_NAMES\.movies,\s*component:\s*\(\)\s*=>\s*import\('\.\.\/views\/Home\.vue'\)/)
    expect(src).toMatch(/ROUTE_PATHS\.landing,\s*name:\s*ROUTE_NAMES\.landing,\s*redirect:\s*ROUTE_PATHS\.home/)
  })
})

describe('stores/user.ts — store 跟着失效广播一起降级', () => {
  const src = code('../../src/stores/user.ts')

  it('订阅失效通知并把 token / user 置空', () => {
    expect(src).toContain('onAuthSessionInvalidated(')
    expect(src).toMatch(/token\.value\s*=\s*''/)
    expect(src).toMatch(/user\.value\s*=\s*null/)
  })
})

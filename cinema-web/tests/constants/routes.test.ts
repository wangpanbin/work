import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  ADMIN_REQUIRED_PREFIX,
  AUTH_REQUIRED_PATHS,
  AUTH_REQUIRED_PREFIXES,
  ROUTE_NAMES,
  ROUTE_PATHS,
  moviePath,
  seatPath,
} from '../../src/constants/routes'

/**
 * 路由常量的契约。
 *
 * <p>**为什么值得锁**: 路由路径此前散在十来个地方的 `router.push('/xxx')` 字面量里,
 * 改一条路径要全仓扫字符串。收进常量后, 「路径只有一份定义」这件事就该由测试守住 ——
 * 否则过几个月又会有人开始写字面量。
 */
describe('路由常量', () => {
  it('路径与路由名一一对应', () => {
    expect(Object.keys(ROUTE_PATHS)).toEqual(Object.keys(ROUTE_NAMES))
  })

  it('路径不重复', () => {
    const paths = Object.values(ROUTE_PATHS)
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('路由名不重复', () => {
    const names = Object.values(ROUTE_NAMES)
    expect(new Set(names).size).toBe(names.length)
  })

  it('关键路径的字面值不变(改路径等于改契约)', () => {
    expect(ROUTE_PATHS.home).toBe('/')
    // 2026-10-26: 根路径归产品主页, 影片浏览页迁到 /movies, 旧 /landing 保留为重定向别名。
    expect(ROUTE_PATHS.movies).toBe('/movies')
    expect(ROUTE_PATHS.landing).toBe('/landing')
    expect(ROUTE_PATHS.login).toBe('/login')
    expect(ROUTE_PATHS.movieDetail).toBe('/movie/:id')
    expect(ROUTE_PATHS.seatSelect).toBe('/seat/:sessionId')
    expect(ROUTE_PATHS.payment).toBe('/payment')
    expect(ROUTE_PATHS.orderList).toBe('/orders')
    expect(ROUTE_PATHS.adminHome).toBe('/admin')
  })

  it('登录校验口径: /payment 与 /orders 精确匹配, /seat 走前缀', () => {
    expect(AUTH_REQUIRED_PATHS).toEqual(['/payment', '/orders'])
    expect(AUTH_REQUIRED_PREFIXES).toEqual(['/seat'])
  })

  it('主页与影片浏览页都是公开页(匿名访客必须能停在上面)', () => {
    expect(AUTH_REQUIRED_PATHS).not.toContain(ROUTE_PATHS.home)
    expect(AUTH_REQUIRED_PATHS).not.toContain(ROUTE_PATHS.movies)
    expect(AUTH_REQUIRED_PREFIXES.some((p) => ROUTE_PATHS.home.startsWith(p))).toBe(false)
    expect(AUTH_REQUIRED_PREFIXES.some((p) => ROUTE_PATHS.movies.startsWith(p))).toBe(false)
  })

  it('管理员校验口径: /admin 前缀', () => {
    expect(ADMIN_REQUIRED_PREFIX).toBe('/admin')
  })

  it('拼路径辅助函数', () => {
    expect(seatPath(1001)).toBe('/seat/1001')
    expect(seatPath('1001')).toBe('/seat/1001')
    expect(moviePath(42)).toBe('/movie/42')
    expect(moviePath('42')).toBe('/movie/42')
  })
})

/**
 * 静态契约 —— 路由表与守卫必须走常量, 不许再写字面量。
 *
 * <p>这条断言的价值在「拦住新写的字面量」: 谁再写 `to.path === '/payment'`,
 * 测试立刻红。
 */
describe('router/index.ts — 走常量而不是字面量', () => {
  const routerSrc = readFileSync(
    fileURLToPath(new URL('../../src/router/index.ts', import.meta.url)),
    'utf-8',
  )

  it('引用了路由常量', () => {
    expect(routerSrc).toContain("from '../constants/routes'")
    expect(routerSrc).toContain('ROUTE_PATHS.home')
    expect(routerSrc).toContain('ROUTE_NAMES.login')
  })

  it('守卫里不再出现硬编码路径字面量', () => {
    expect(routerSrc).not.toContain("to.path === '/payment'")
    expect(routerSrc).not.toContain("to.path === '/orders'")
    expect(routerSrc).not.toContain("to.path.startsWith('/seat')")
    expect(routerSrc).not.toContain("to.path.startsWith('/admin')")
  })
})

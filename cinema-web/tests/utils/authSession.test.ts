import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import {
  invalidateAuthSession,
  onAuthSessionInvalidated,
} from '../../src/utils/authSession'
import { AUTH_TOKEN_KEY, AUTH_USER_KEY } from '../../src/constants/auth'

/**
 * 登录态失效的广播契约(2026-10-26)。
 *
 * <p>**为什么值得锁**: 登录态有**两份** —— localStorage 与 Pinia store。
 * 拦截器过去只清 localStorage, store 里的 token 还是旧值 →
 * `isLogin` 仍为 true → 顶栏照显示已登录、守卫照放行 →
 * 后续请求继续 401 → 死循环。这份测试锁住「**一次失效, 两份同时死**」。
 */

/** node 环境没有 localStorage —— 装一个最小的内存实现。 */
class MemoryStorage {
  private map = new Map<string, string>()
  getItem(k: string): string | null {
    return this.map.has(k) ? (this.map.get(k) as string) : null
  }
  setItem(k: string, v: string): void {
    this.map.set(k, String(v))
  }
  removeItem(k: string): void {
    this.map.delete(k)
  }
  clear(): void {
    this.map.clear()
  }
}

const g = globalThis as unknown as { localStorage?: MemoryStorage }
const original = g.localStorage

describe('登录态失效广播', () => {
  beforeEach(() => {
    g.localStorage = new MemoryStorage()
  })

  afterEach(() => {
    g.localStorage = original
  })

  it('清掉 localStorage 里的 token 与 user 缓存', () => {
    g.localStorage!.setItem(AUTH_TOKEN_KEY, 'stale.jwt.value')
    g.localStorage!.setItem(AUTH_USER_KEY, '{"id":1}')

    invalidateAuthSession()

    expect(g.localStorage!.getItem(AUTH_TOKEN_KEY)).toBeNull()
    expect(g.localStorage!.getItem(AUTH_USER_KEY)).toBeNull()
  })

  it('通知订阅者(store 靠它把 token / user 置空)', () => {
    let called = 0
    const off = onAuthSessionInvalidated(() => {
      called++
    })

    invalidateAuthSession()
    expect(called).toBe(1)

    // 幂等: 重复调用只是再通知一次, 不该抛错也不该重复注册
    invalidateAuthSession()
    expect(called).toBe(2)

    off()
    invalidateAuthSession()
    expect(called).toBe(2)
  })

  it('订阅者抛错不影响其他人, 也不影响失效本身', () => {
    let reached = 0
    const off1 = onAuthSessionInvalidated(() => {
      throw new Error('boom')
    })
    const off2 = onAuthSessionInvalidated(() => {
      reached++
    })

    expect(() => invalidateAuthSession()).not.toThrow()
    expect(reached).toBe(1)
    expect(g.localStorage!.getItem(AUTH_TOKEN_KEY)).toBeNull()

    off1()
    off2()
  })

  it('没有 localStorage 时(隐私模式)不抛错', () => {
    g.localStorage = undefined as unknown as MemoryStorage
    expect(() => invalidateAuthSession()).not.toThrow()
  })
})

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setLocale } from '../../src/i18n/persist'
import { LOCALE_STORAGE_KEY } from '../../src/i18n/detect'

/**
 * persist.test.ts
 *
 * 第二性原则 (TDD seam):
 * - `setLocale(next, onApply)` 是副作用边界函数:
 *     1. 写 localStorage (持久化)
 *     2. 调用 onApply(next) (通知 Vue i18n reactive 更新)
 * - seam = 函数本身;不耦合 vue-i18n 实例 — 由 caller 注入 onApply
 * - 不抛错即使 localStorage 不可用 (e.g. SSR / privacy mode)
 */

describe('setLocale — 契约(持久化 + 通知)', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('调用后,localStorage.setItem 被调用一次,key=cinema_locale,value=next', () => {
    const setItem = vi.fn()
    vi.stubGlobal('localStorage', { getItem: vi.fn(), setItem })

    setLocale('en-US', vi.fn())

    expect(setItem).toHaveBeenCalledTimes(1)
    expect(setItem).toHaveBeenCalledWith(LOCALE_STORAGE_KEY, 'en-US')
  })

  it('调用后,onApply 回调被调用一次,参数是 next', () => {
    vi.stubGlobal('localStorage', { getItem: vi.fn(), setItem: vi.fn() })
    const onApply = vi.fn()

    setLocale('zh-CN', onApply)

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith('zh-CN')
  })

  it('localStorage 不存在时(SSR / 隐私模式),不抛错,onApply 仍调用', () => {
    vi.stubGlobal('localStorage', undefined)
    const onApply = vi.fn()

    expect(() => setLocale('en-US', onApply)).not.toThrow()
    expect(onApply).toHaveBeenCalledWith('en-US')
  })
})
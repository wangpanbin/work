import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { makeMissingWarnWrapper } from '../../src/i18n/fallback'

/**
 * fallback.test.ts
 *
 * 第三性原则 (TDD seam):
 * - 核心纯函数: wrap 一个 t 函数,当 t 返回值等于 key 自身时(缺翻译),
 *   在开发态 console.warn,生产态静默
 * - 抽出纯函数让 useT() composable 只是这个函数 + vue-i18n glue,可独立测
 */

describe('makeMissingWarnWrapper — 缺翻译时的开发态提示', () => {
  let warnSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    warnSpy.mockRestore()
  })

  it('t 返回值 !== key 时,不 warn,直接透传', () => {
    const fakeT = (key: string) => (key === 'app.brand' ? '星辉影城' : key)
    const wrapped = makeMissingWarnWrapper(fakeT, () => 'zh-CN', true)

    expect(wrapped('app.brand')).toBe('星辉影城')
    expect(warnSpy).not.toHaveBeenCalled()
  })

  it('t 返回值 === key(缺翻译)时,isDev=true 触发 console.warn,参数含 key 和当前 locale', () => {
    const fakeT = (key: string) => key  // 模拟全部 missing
    const wrapped = makeMissingWarnWrapper(fakeT, () => 'en-US', true)

    wrapped('home.uncategorized')

    expect(warnSpy).toHaveBeenCalledTimes(1)
    const [firstArg] = warnSpy.mock.calls[0]
    expect(String(firstArg)).toContain('home.uncategorized')
    expect(String(firstArg)).toContain('en-US')
  })

  it('t 返回值 === key 时,isDev=false 静默 — 不 console.warn', () => {
    const fakeT = (key: string) => key
    const wrapped = makeMissingWarnWrapper(fakeT, () => 'en-US', false)

    wrapped('home.uncategorized')

    expect(warnSpy).not.toHaveBeenCalled()
  })

  it('返回 t 的实际值(透传)— 即使参数对象是空', () => {
    const fakeT = (key: string, _params?: unknown) => (key === 'app.brand' ? 'Star Cinema' : key)
    const wrapped = makeMissingWarnWrapper(fakeT, () => 'en-US', true)

    expect(wrapped('app.brand', {})).toBe('Star Cinema')
    expect(wrapped('unknown.key')).toBe('unknown.key')  // missing → 透传 key 本身
    expect(warnSpy).toHaveBeenCalledTimes(1)
  })
})
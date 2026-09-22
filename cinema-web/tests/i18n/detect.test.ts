import { describe, it, expect, beforeEach, vi } from 'vitest'
import { detectInitialLocale, LOCALE_STORAGE_KEY, SUPPORTED_LOCALES } from '../../src/i18n/detect'

/**
 * detect.test.ts
 *
 * 第一性原则 (TDD seam):
 * - `detectInitialLocale()` 是纯函数,输入 globalThis.localStorage + navigator.language,输出 SupportedLocale
 * - localStorage 是最高优先级(用户上次主动选的优先于浏览器语言)
 * - 测试 seam 是函数本身,不依赖 Vue runtime
 * - mock 用 vi.stubGlobal,确保副作用隔离到本测试
 */

describe('detectInitialLocale — 契约(优先级 + 支持集合)', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('localStorage 已有 cinema_locale = "en-US" 时,直接返回 en-US(不管 navigator.language)', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn((k: string) => (k === LOCALE_STORAGE_KEY ? 'en-US' : null)),
      setItem: vi.fn(),
    })
    vi.stubGlobal('navigator', { language: 'zh-CN' })

    expect(detectInitialLocale()).toBe('en-US')
    expect(SUPPORTED_LOCALES).toContain('en-US')
  })

  it('localStorage 已有 cinema_locale = "zh-CN" 时,直接返回 zh-CN', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn((k: string) => (k === LOCALE_STORAGE_KEY ? 'zh-CN' : null)),
      setItem: vi.fn(),
    })
    vi.stubGlobal('navigator', { language: 'en-US' })

    expect(detectInitialLocale()).toBe('zh-CN')
  })

  it('localStorage 无值 + navigator.language="en-US" 时,嗅探返回 en-US', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
      setItem: vi.fn(),
    })
    vi.stubGlobal('navigator', { language: 'en-US' })

    expect(detectInitialLocale()).toBe('en-US')
  })

  it('localStorage 无值 + navigator.language="zh-CN" 时,嗅探返回 zh-CN', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
      setItem: vi.fn(),
    })
    vi.stubGlobal('navigator', { language: 'zh-CN' })

    expect(detectInitialLocale()).toBe('zh-CN')
  })

  it('localStorage 无值 + navigator.language="zh-TW" 时,嗅探归 zh-CN', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
      setItem: vi.fn(),
    })
    vi.stubGlobal('navigator', { language: 'zh-TW' })

    expect(detectInitialLocale()).toBe('zh-CN')
  })

  it('localStorage 无值 + navigator.language="ja-JP"(非 zh/en)时,默认 zh-CN', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => null),
      setItem: vi.fn(),
    })
    vi.stubGlobal('navigator', { language: 'ja-JP' })

    expect(detectInitialLocale()).toBe('zh-CN')
  })

  it('localStorage 存了不合法值(如 "fr-FR")时,降级到 navigator 嗅探(非合法值不抛错)', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn((k: string) => (k === LOCALE_STORAGE_KEY ? 'fr-FR' : null)),
      setItem: vi.fn(),
    })
    vi.stubGlobal('navigator', { language: 'en-US' })

    expect(detectInitialLocale()).toBe('en-US')
  })
})
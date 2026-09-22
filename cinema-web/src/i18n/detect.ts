/**
 * detect.ts — 语言嗅探 (i18n 基础设施 slice 1)
 *
 * 单一职责:从 localStorage 和 navigator.language 推断初始语言。
 * - 不依赖 Vue / createI18n — 纯函数,可独立单测
 * - 不写副作用(setLocale 持久化在 persist.ts)
 */

export const LOCALE_STORAGE_KEY = 'cinema_locale'

export const SUPPORTED_LOCALES = ['zh-CN', 'en-US'] as const
export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number]

/**
 * 嗅探优先级:localStorage 显式 > navigator.language 前缀 > 默认 zh-CN
 *
 * - localStorage 已有合法值 → 尊重用户上次主动选择
 * - navigator.language 以 zh 开头 → zh-CN (zh-TW / zh-HK 也归 zh-CN)
 * - navigator.language 以 en 开头 → en-US (en-GB / en-AU 也归 en-US)
 * - 其他 / 缺失 → 默认 zh-CN (项目主语言)
 */
export function detectInitialLocale(): SupportedLocale {
  const saved = globalThis.localStorage?.getItem(LOCALE_STORAGE_KEY)
  if (saved && (SUPPORTED_LOCALES as readonly string[]).includes(saved)) {
    return saved as SupportedLocale
  }
  const lang = globalThis.navigator?.language ?? ''
  if (lang.startsWith('zh')) return 'zh-CN'
  if (lang.startsWith('en')) return 'en-US'
  return 'zh-CN'
}
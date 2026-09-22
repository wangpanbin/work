/**
 * persist.ts — 语言持久化 + 通知 (i18n 基础设施 slice 2)
 *
 * 单一职责:把"切换语言"翻译成两个副作用 ——
 *   1. 写 localStorage(刷新后保持)
 *   2. 调用 onApply 回调(让 Vue i18n reactive 立即更新)
 *
 * 不依赖 vue-i18n 实例(由 caller 注入 onApply),保证可单测。
 */

import { LOCALE_STORAGE_KEY, type SupportedLocale } from './detect'

/**
 * 切换语言。
 *
 * @param next 目标语言(必须是 SupportedLocale)
 * @param onApply Vue i18n reactive 更新回调(由 caller 注入)
 *
 * 行为契约:
 *   - 永远调用 onApply(next),即使 localStorage 不可用(SSR / 隐私模式)
 *   - localStorage.setItem 失败/不存在时,不抛错(降级为无持久化)
 */
export function setLocale(
  next: SupportedLocale,
  onApply: (locale: SupportedLocale) => void,
): void {
  try {
    globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, next)
  } catch {
    // localStorage 写入失败(配额满 / 隐私模式),不阻断切换本身
  }
  onApply(next)
}
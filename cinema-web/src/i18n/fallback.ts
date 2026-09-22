/**
 * fallback.ts — 缺翻译开发态提示 (i18n 基础设施 slice 3)
 *
 * 单一职责:包一层 t 函数,在缺翻译时开发态 console.warn,生产态静默。
 *
 * 设计:
 * - vue-i18n 的 `t(key)` 在 key 不存在时返回 key 本身(字符串)
 * - 我们通过检测 "返回值 === key" 来判定缺翻译
 * - 抽成纯函数 `makeMissingWarnWrapper` 让核心逻辑可单测
 * - `useT()` 是 Vue composable 入口,把 useI18n().t 喂给 wrapper
 */

import { useI18n } from 'vue-i18n'

/**
 * 类型:t = t(window 函数),返回 string | VNode(模板用)。我们只关心 string。
 */
type RawT = (key: string, params?: Record<string, unknown>) => unknown
type LocaleGetter = () => string

/**
 * 包装 t 函数:缺翻译时开发态 console.warn,生产态静默;返回始终是 string(模板用法)。
 *
 * seam:纯函数,接收 (rawT, localeGetter, isDev),返回包装后的 t。测试不依赖 vue-i18n。
 */
export function makeMissingWarnWrapper(
  rawT: RawT,
  localeGetter: LocaleGetter,
  isDev: boolean,
): (key: string, params?: Record<string, unknown>) => string {
  return function wrappedT(key: string, params?: Record<string, unknown>): string {
    const value = rawT(key, params)
    if (value === key) {
      if (isDev) {
        console.warn(`[i18n] Missing translation for "${key}" in locale "${localeGetter()}" — showing key as fallback`)
      }
      return key  // 与 vue-i18n 行为一致:缺翻译直接显示 key
    }
    return String(value)
  }
}

/**
 * Vue composable 入口 — 在 setup() 里调。
 * 用法:`const { t, locale } = useT()` 然后模板里 `{{ t('app.brand') }}`
 *
 * `isDev` 通过 `import.meta.env.DEV` 推断(Vite 内置)。
 */
export function useT() {
  const { t: rawT, locale } = useI18n()
  // vue-i18n 的 t 是 overload,实际签名是 (key, params) → string | VNodeList
  const t = makeMissingWarnWrapper(
    rawT as RawT,
    () => locale.value,
    Boolean(import.meta.env.DEV),
  )
  return { t, locale }
}
/**
 * stores/i18n.ts — i18n 的 Pinia 包装
 *
 * 暴露给模板 / 组件用:
 *   const store = useI18nStore()
 *   store.locale         // 当前语言(reactive)
 *   store.switchLocale('en-US')
 *
 * 单一职责:把 detect / persist / vue-i18n 三者胶水在一起,业务组件不需要直接耦合 vue-i18n。
 */

import { defineStore } from 'pinia'
import { computed } from 'vue'
import { i18n, i18nSetLocale } from '../i18n'
import type { SupportedLocale } from '../i18n/detect'

export const useI18nStore = defineStore('i18n', () => {
  const locale = computed<SupportedLocale>(() => i18n.global.locale.value as SupportedLocale)

  function switchLocale(next: SupportedLocale) {
    i18nSetLocale(next)
  }

  /** "切到另一个语言" — 给 chip 按钮用,无需 caller 知道当前是哪个 */
  function toggleLocale() {
    switchLocale(locale.value === 'zh-CN' ? 'en-US' : 'zh-CN')
  }

  return { locale, switchLocale, toggleLocale }
})
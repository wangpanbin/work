/**
 * stores/theme.ts — 主题的 Pinia 包装
 *
 * 暴露给模板 / 组件用:
 *   const store = useThemeStore()
 *   store.pref      // 当前偏好(跟随系统/亮/暗)—— reactive
 *   store.resolved  // 实际生效主题(亮/暗)—— reactive
 *   store.cycle()   // 三态循环,给 header 芯片用
 *
 * 单一职责:把 theme/ 单例胶水给组件,业务组件不直接耦合 theme 模块。
 * 与 stores/i18n.ts 同构。
 */

import { defineStore } from 'pinia'
import { cycleThemePref, themeState } from '../theme'

export const useThemeStore = defineStore('theme', () => {
  const { pref, resolved } = themeState()

  /**
   * 三态循环: 跟随系统 → 亮 → 暗 → 跟随系统。
   * 芯片只要这一个动作 —— 设成某一态的能力留在 theme 模块的 setThemePref(),
   * 组件不必拿到一个当前没有调用方的公开 API。
   */
  function cycle() {
    cycleThemePref()
  }

  return { pref, resolved, cycle }
})

/**
 * theme/index.ts — 主题运行时(单例)
 *
 * 职责边界:
 *   resolve.ts  纯函数,可单测
 *   apply.ts    写 DOM
 *   index.ts    把两者接到真实运行时:初始化 / 切换 / 跟随系统
 *   stores/theme.ts  Pinia 包装,给组件用
 *
 * 为什么状态放这里而不是组件里:
 *   主题是全站状态,header 芯片要读写它、路由切换后要保持它、
 *   系统主题变化时它要自己响应。放在模块单例里,这三件事都不需要组件参与。
 */

import { readonly, ref } from 'vue'
import { applyTheme } from './apply'
import {
  nextPref,
  readStoredPref,
  resolveTheme,
  writeStoredPref,
  type ResolvedTheme,
  type ThemePref,
} from './resolve'

/** 用户偏好(可写) */
const pref = ref<ThemePref>('auto')
/** 实际生效主题(只读) */
const resolved = ref<ResolvedTheme>('light')

/** 系统是否偏好深色。不支持 matchMedia 的老浏览器一律按浅色处理。 */
function systemPrefersDark(): boolean {
  try {
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  } catch {
    return false
  }
}

/** 重新解析并落到 DOM 上。 */
function sync(): void {
  resolved.value = resolveTheme(pref.value, systemPrefersDark())
  applyTheme(resolved.value)
}

/**
 * 初始化主题。
 *
 * 必须在 Vue 挂载前调用(index.html 里的内联脚本已经先把首帧定好,
 * 这里是补齐 favicon / 订阅系统变化 / 读取持久化偏好)。
 */
export function initTheme(): void {
  pref.value = readStoredPref()
  sync()
  subscribeSystemTheme(() => {
    if (pref.value === 'auto') sync()
  })
}

/**
 * matchMedia 查询对象的最小接口。
 * 只声明本模块用到的两个能力, 便于单测注入替身(本仓 vitest 是 node 环境, 没有 matchMedia)。
 */
export interface MediaQueryLike {
  matches: boolean
  addEventListener?: (type: string, cb: () => void) => void
  addListener?: (cb: () => void) => void
}

/**
 * 订阅系统主题变化。
 *
 * 只在偏好为「跟随系统」时才需要响应 —— 用户显式选了亮/暗时,
 * 系统变化不应推翻他的选择(调用方负责这个判断)。
 *
 * @param onChange 系统主题变了
 * @param mq       注入的 matchMedia 替身; **显式传 null 表示「没有 matchMedia」**,
 *                 省略( undefined)则取真实的 window.matchMedia
 */
export function subscribeSystemTheme(
  onChange: () => void,
  mq?: MediaQueryLike | null,
): void {
  try {
    const query =
      mq === undefined
        ? typeof window !== 'undefined'
          ? window.matchMedia?.('(prefers-color-scheme: dark)')
          : undefined
        : mq
    if (!query) return
    if (typeof query.addEventListener === 'function') {
      query.addEventListener('change', onChange)
    } else if (typeof query.addListener === 'function') {
      // Safari < 14 的老 API
      query.addListener(onChange)
    }
  } catch {
    // 订阅失败只影响「系统主题实时跟随」,不影响手动切换
  }
}

/** 设置偏好(并持久化 + 立即生效)。 */
export function setThemePref(next: ThemePref): void {
  pref.value = next
  writeStoredPref(next)
  sync()
}

/** 三态循环:跟随系统 → 亮 → 暗 → 跟随系统。给 header 芯片用。 */
export function cycleThemePref(): void {
  setThemePref(nextPref(pref.value))
}

/** 组件用的只读状态。 */
export function themeState() {
  return { pref: readonly(pref), resolved: readonly(resolved) }
}

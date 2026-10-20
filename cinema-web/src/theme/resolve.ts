/**
 * theme/resolve.ts — 主题偏好的纯函数层
 *
 * 单一职责:把「用户偏好(3 态)」与「系统偏好(布尔)」解析成「实际主题(2 态)」。
 * 本文件不碰 DOM、不碰 localStorage,便于单测(见 tests/styles/themeSwitch.test.ts)。
 *
 * 为什么是 3 态偏好而不是 2 态开关:
 *   「跟随系统」是默认值 —— 深色系统用户首屏就该是暗的,不该先闪一帧白。
 *   若只有 亮/暗 两态,默认亮色会让深色系统用户每次首访都白闪。
 */

/** 用户偏好:跟随系统 / 强制亮 / 强制暗 */
export type ThemePref = 'auto' | 'light' | 'dark'

/** 实际生效的主题(只有两套色板) */
export type ResolvedTheme = 'light' | 'dark'

/** localStorage 键名。与 i18n 的 locale key 同构:一个偏好一个键。 */
export const THEME_STORAGE_KEY = 'cinema_theme'

/** 全部合法偏好值 */
export const THEME_PREFS: readonly ThemePref[] = ['auto', 'light', 'dark'] as const

/** 运行时类型守卫:外部值(尤其是 localStorage 里可能被手改的字符串)是否合法 */
export function isThemePref(value: unknown): value is ThemePref {
  return typeof value === 'string' && (THEME_PREFS as readonly string[]).includes(value)
}

/**
 * 解析实际主题。
 *
 * @param pref 用户偏好
 * @param systemPrefersDark 系统是否偏好深色
 * @returns 实际生效的主题
 */
export function resolveTheme(pref: ThemePref, systemPrefersDark: boolean): ResolvedTheme {
  if (pref === 'dark') return 'dark'
  if (pref === 'light') return 'light'
  return systemPrefersDark ? 'dark' : 'light'
}

/**
 * 取实际可用的 storage。
 *
 * ⚠️ 省略参数时必须回落到 `globalThis.localStorage` —— 早先写成
 *    `storage?.getItem(...)`, 省略参数时 `storage` 是 undefined, 于是永远读到
 *    undefined、偏好恒为 'auto'。调用方不传参是常态, 不是异常。
 */
function resolveStorage<T extends 'getItem' | 'setItem'>(
  storage: Pick<Storage, T> | null | undefined,
): Pick<Storage, T> | null {
  if (storage !== undefined) return storage
  try {
    return (globalThis.localStorage ?? null) as Pick<Storage, T> | null
  } catch {
    // 某些沙箱环境访问 localStorage 本身就会抛 SecurityError
    return null
  }
}

/**
 * 读取已存偏好。
 *
 * 降级策略:读不到 / 值非法 / localStorage 不可用(隐私模式、SSR)一律回 'auto'。
 * 读取失败绝不能抛错 —— 主题只是外观,不值得让整个应用起不来。
 */
export function readStoredPref(storage?: Pick<Storage, 'getItem'> | null): ThemePref {
  try {
    const raw = resolveStorage(storage)?.getItem(THEME_STORAGE_KEY)
    return isThemePref(raw) ? raw : 'auto'
  } catch {
    return 'auto'
  }
}

/**
 * 写入偏好。写失败(配额满 / 隐私模式)静默降级,不阻断切换本身。
 */
export function writeStoredPref(pref: ThemePref, storage?: Pick<Storage, 'setItem'> | null): void {
  try {
    resolveStorage(storage)?.setItem(THEME_STORAGE_KEY, pref)
  } catch {
    // 持久化失败只影响「刷新后保持」,不影响本次切换
  }
}

/**
 * 三态循环:跟随系统 → 亮 → 暗 → 跟随系统。
 * 给 header 芯片用,调用方无需知道当前处于哪一态。
 */
export function nextPref(current: ThemePref): ThemePref {
  if (current === 'auto') return 'light'
  if (current === 'light') return 'dark'
  return 'auto'
}

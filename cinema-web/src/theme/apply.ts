/**
 * theme/apply.ts — 把解析出来的主题写到 DOM 上
 *
 * 两件事:
 *   1. `data-theme` 属性 → CSS 的作用域开关(main.css 里 `html[data-theme='dark']` 覆盖 token)
 *   2. `dark` 类名 → Element Plus 官方暗色变量表(element-plus/theme-chalk/dark/css-vars.css)
 *
 * 为什么用 `data-theme` 而不是直接用 EP 的 `html.dark`:
 *   现有闸门 themeTokens.test.ts 的 parseRootTokens() 只匹配 `:root { }` 块。
 *   若把暗色 token 写进 `:root`, 旧闸门会把两套值合并、只测到最后一套 → 闸门静默失效。
 *   `html[data-theme='dark']` 不被那个正则匹配, 迁移期旧闸门完全不受影响。
 *
 * 为什么还要挂 `dark` 类名:
 *   EP 官方暗色变量表的选择器写死是 `html.dark`, 我们不重写它, 只把它的开关接上。
 */

import { applyFavicon } from './favicon'
import type { ResolvedTheme } from './resolve'

/**
 * 把主题写到根元素上。
 *
 * - `data-theme` 属性:我们自己的 token 作用域开关
 * - `dark` 类名:Element Plus 官方暗色变量表的开关
 */
export function applyTheme(theme: ResolvedTheme, doc?: Document): void {
  const d = doc ?? document
  const root = d.documentElement
  root.setAttribute('data-theme', theme)
  root.classList.toggle('dark', theme === 'dark')
  applyFavicon(theme, d)
}

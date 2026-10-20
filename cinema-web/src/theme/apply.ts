/**
 * theme/apply.ts — 把解析出来的主题写到 DOM 上
 *
 * 两件事:
 *   1. `data-theme` 属性 → CSS 的作用域开关(main.css 里 `html[data-theme='dark']` 覆盖 token)
 *   2. `dark` 类名 → Element Plus 官方暗色变量表(element-plus/theme-chalk/dark/css-vars.css)
 *
 * 为什么用 `data-theme` 而不是直接用 EP 的 `html.dark`:
 *   现有闸门 themeTokens.test.ts 的 parseRootTokens() 只匹配 `:root { }` 块。
 *   若把暗色 token 写进 `:root`,旧闸门会把两套值合并、只测到最后一套 → 闸门静默失效。
 *   `html[data-theme='dark']` 不被那个正则匹配,迁移期旧闸门完全不受影响。
 *
 * 为什么还要挂 `dark` 类名:
 *   EP 官方暗色变量表的选择器写死是 `html.dark`,我们不重写它,只把它的开关接上。
 */

import type { ResolvedTheme } from './resolve'

/**
 * favicon:两套内联 SVG data-URI。
 * 造型与 BrandMark.vue 同源(票面框 + 齿孔虚线 + 条码),只换底色与印色。
 *
 * ⚠️ 浅色那份与 index.html 里的静态 <link> 必须逐字一致 —— 由 themeTokens 闸门锁住。
 *    闸门会校验:浅色用浅色模式的 --paper/--accent,暗色用暗色模式的 --paper/--accent。
 */
const SVG_TICKET =
  "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E" +
  "%3Crect width='32' height='32' rx='7' fill='%23PAPER%'/%3E" +
  "%3Crect x='5.5' y='8' width='21' height='16' rx='2' fill='none' stroke='%23INK%' stroke-width='2'/%3E" +
  "%3Cline x1='15' y1='10.5' x2='15' y2='21.5' stroke='%23INK%' stroke-width='2' stroke-linecap='round' stroke-dasharray='0.1 3'/%3E" +
  "%3Crect x='18' y='12' width='1.6' height='8' fill='%23INK%'/%3E" +
  "%3Crect x='21' y='12' width='1' height='8' fill='%23INK%'/%3E" +
  "%3Crect x='23.6' y='12' width='1.8' height='8' fill='%23INK%'/%3E" +
  '%3C/svg%3E'

/** 浅色 favicon:纸 #eef1f6 / 印 #2563eb */
export const FAVICON_LIGHT = `data:image/svg+xml,${SVG_TICKET.replace(/%23PAPER%/g, '%23eef1f6').replace(/%23INK%/g, '%232563eb')}`

/** 暗色 favicon:纸 #0a0f1a / 印 #3b82f6 */
export const FAVICON_DARK = `data:image/svg+xml,${SVG_TICKET.replace(/%23PAPER%/g, '%230a0f1a').replace(/%23INK%/g, '%233b82f6')}`

/** favicon <link> 的 id,index.html 与本模块共用 */
export const FAVICON_LINK_ID = 'cinema-favicon'

/**
 * 切换 favicon。找不到 <link> 时静默跳过(favicon 不是关键路径)。
 */
export function applyFavicon(theme: ResolvedTheme, doc?: Document): void {
  try {
    const d = doc ?? document
    const link = d.getElementById(FAVICON_LINK_ID) as HTMLLinkElement | null
    if (!link) return
    link.href = theme === 'dark' ? FAVICON_DARK : FAVICON_LIGHT
  } catch {
    // favicon 失败不影响页面
  }
}

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

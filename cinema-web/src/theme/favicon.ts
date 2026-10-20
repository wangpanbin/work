/**
 * theme/favicon.ts — 标签页图标(两套)
 *
 * <p>造型与 BrandMark.vue 同源(票面框 + 齿孔虚线 + 条码), 只换底色与印色:
 * 浅色用纸 #eef1f6 / 印 #2563eb, 暗色用纸 #0a0f1a / 印 #3b82f6。
 *
 * <p><b>为什么 favicon 也要两套</b>: 它是 data-URI, token 够不着它 ——
 * 闸门 `themeTokens.test.ts` 直接把这两个常量里的 hex 跟当前模式的
 * `--paper` / `--accent` 逐值比对, 漂了就判红。
 */

import type { ResolvedTheme } from './resolve'

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

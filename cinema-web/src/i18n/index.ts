/**
 * index.ts — i18n 模块入口 (slice 4 — createI18n 装配)
 *
 * 装配 createI18n 实例,接入字典 + 嗅探到的初始 locale。
 * Vue 应用通过 `app.use(i18n)` 挂载。
 */

import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN'
import enUS from './locales/en-US'
import { detectInitialLocale, type SupportedLocale } from './detect'
import { setLocale } from './persist'

/**
 * 单例 i18n 实例。模板和 composable 都从这个实例读 locale + t。
 *
 * 配置说明:
 * - legacy: false → 使用 composition API (useI18n)
 * - fallbackLocale: 'zh-CN' → 任何 locale 缺 key 自动回退到中文(不会显示 raw key)
 * - messages: zh-CN + en-US 两套;后续加 ja-JP / ko-KR 在这里继续注册
 */
export const i18n = createI18n({
  legacy: false,
  locale: detectInitialLocale(),
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS,
  },
})

/**
 * 把当前 locale 同步到 `<html lang>`。
 *
 * index.html 里的 `lang="zh-CN"` 只是 JS 执行前的默认值 —— 屏幕阅读器与浏览器
 * 翻译/断词都读这个属性, 用户切到英文后它必须跟着变, 否则英文页面仍被当作中文朗读。
 * 非浏览器环境(node 测试)无 document, 静默跳过。
 */
function applyHtmlLang(locale: SupportedLocale): void {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = locale
  }
}

applyHtmlLang(i18n.global.locale.value as SupportedLocale)

/**
 * 切换语言的胶水函数。
 *
 * 把"setLocale 的持久化逻辑"和"vue-i18n 的 reactive locale 更新"绑在一起,
 * 这样 store/callsite 只需要调一次。
 *
 * 用法:`i18nSetLocale('en-US')` — 同步写 + 同步改 reactive locale(spec §3.3)。
 *
 * **同步语义**:此处必须用静态 import;若用 `import().then(...)` 会把 locale
 * 更新推到 microtask,导致同 tick 内的 read-after-write 拿到旧值。
 */
export function i18nSetLocale(next: SupportedLocale): void {
  setLocale(next, (l) => {
    i18n.global.locale.value = l
    applyHtmlLang(l)
  })
}
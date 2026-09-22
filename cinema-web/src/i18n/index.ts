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
  })
}
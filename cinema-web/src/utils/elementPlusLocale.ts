/**
 * elementPlusLocale — 把应用语言映射到 Element Plus 组件库语言包。
 *
 * <p>**P3-5**:之前只 `app.use(ElementPlus)` 没传 locale,Element Plus 用英文默认包。
 * 表现是中文界面下 popconfirm 的确认/取消按钮显示成 "No / Yes"(E2E 2026-09-29 实测)。
 *
 * <p>**为什么用 `<el-config-provider>` 而不是 `app.use(ElementPlus, { locale })`**:
 * 后者只在应用挂载时生效一次,用户在顶栏切换语言后组件库文案不会跟着变(仍是旧语言)。
 * ConfigProvider 是响应式的,能随 `i18nStore.locale` 实时切换。
 *
 * <p>语言包是**同步 import** 而非动态 import:locale 文件很小,而且动态 import 会让
 * 首次切换出现一帧闪动,反而不如静态引入稳定。
 */
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import type { SupportedLocale } from '../i18n/detect'

/** 从语言包本身推导类型,避免依赖 element-plus 内部类型导出路径 */
type EpLanguage = typeof zhCn

const ELEMENT_PLACE_LOCALES: Record<SupportedLocale, EpLanguage> = {
  'zh-CN': zhCn,
  'en-US': en as EpLanguage,
}

/** 取指定应用语言对应的 Element Plus 语言包;未知/缺失语言回退中文 */
export function elementPlusLocale(locale: string | undefined): EpLanguage {
  return ELEMENT_PLACE_LOCALES[(locale as SupportedLocale) ?? 'zh-CN'] ?? zhCn
}

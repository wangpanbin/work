import { i18n } from './index'

/**
 * 业务错误文案的本地化兜底。
 *
 * <p>**后端消息是中文的** —— 英文界面直接透出会「英文里夹中文」。
 * 中文界面保留后端原文(信息量最大: 「场次已开场」比「请求失败」有用得多);
 * 其它界面退化为本地化兜底文案。后端原文仍挂在 `error.message` 上,
 * 需要细节的调用方可以自己取。
 *
 * <p>统一放这里, 免得「拦截器一套规则、组件另一套规则」。
 *
 * @param detail       后端原文(可空)
 * @param fallbackKey  本地化兜底文案键; 缺省 `api.requestFailed`
 */
export function errorText(detail: string | undefined, fallbackKey = 'api.requestFailed'): string {
  const locale = String(i18n.global.locale.value ?? '')
  if (locale.startsWith('zh')) return detail || i18n.global.t(fallbackKey)
  return i18n.global.t(fallbackKey)
}

/**
 * 场次状态视图模型 (与 `views/order/constants.ts` 同构, 供 ORDER_STATUS_VIEW 的读法参照)。
 *
 * <p><b>为什么独立成模块</b>: 2026-10-12 场次管理状态列整列显示「未知」。根因是
 * `SessionManage.vue` 里内联的映射数组把 <b>下标 1(在售)</b> 误写成空串 `''`,
 * 而模板用「取到真值才翻译, 否则回落 unknown」的写法 —— 空串是 falsy, 于是
 * **演示数据里最常见的 status=1** 全部回落成「未知」。
 *
 * <p>把这段逻辑下沉到纯函数后有两个收益:
 * ① 本仓库 vitest 跑在 `environment: 'node'`、没装 jsdom / @vue/test-utils,
 *    挂不上 SFC (见 AGENTS.md); 下沉后才能写**行为断言**而不是只能做源码正则扫描;
 * ② 映射表用 `Record<SessionStatusCode, string>` 表达, 新增/删除状态码时
 *    TypeScript 会直接报缺失, 不会再靠「空串占位」蒙混过关。
 *
 * <p><b>wire-format</b>: 状态码语义以后端为唯一事实来源, 见
 * `cinema-server/.../modules/session/entity/Session.java` 与 `dto/SessionDTO.java`
 * 的注释「0待开售 1在售 2已开场 3已结束」。改这里必须同步改后端, 反之亦然。
 * 回归锁见 `tests/views/admin/sessionStatusLabel.test.ts`。
 */

/** 场次状态码 —— 与后端 `Session.status` 一一对应 */
export const SESSION_STATUS = {
  /** 0 待开售 */
  PENDING: 0,
  /** 1 在售 */
  SELLING: 1,
  /** 2 已开场 */
  STARTED: 2,
  /** 3 已结束 */
  ENDED: 3,
} as const

export type SessionStatusCode = (typeof SESSION_STATUS)[keyof typeof SESSION_STATUS]

/** 全部合法状态码, 供 `v-for` 渲染下拉/标签用 */
export const SESSION_STATUS_CODES = [
  SESSION_STATUS.PENDING,
  SESSION_STATUS.SELLING,
  SESSION_STATUS.STARTED,
  SESSION_STATUS.ENDED,
] as const

/**
 * status -> i18n key。
 *
 * <p><b>每个 code 都必须有非空值</b>: 本表不允许出现空串/占位符。
 * 模板一律走 {@link resolveSessionStatusLabelKey}, 它只接受真值 key,
 * 空串会被当成「未收录」而静默回落成「未知」—— 那正是本次缺陷的形态。
 */
export const SESSION_STATUS_LABEL_KEY: Record<SessionStatusCode, string> = {
  [SESSION_STATUS.PENDING]: 'admin.sessionStatusPending',
  [SESSION_STATUS.SELLING]: 'admin.sessionStatusSelling',
  [SESSION_STATUS.STARTED]: 'admin.sessionStatusStarted',
  [SESSION_STATUS.ENDED]: 'admin.sessionStatusEnded',
}

/** 未收录/异常状态码的兜底文案 key */
export const SESSION_STATUS_UNKNOWN_KEY = 'admin.sessionStatusUnknown'

/**
 * 把后端 status 解析成 i18n key。
 *
 * <p>容错: `null` / `undefined` / `NaN` / 越界 code / 非数值 / 空串 一律回落
 * {@link SESSION_STATUS_UNKNOWN_KEY}, 不会返回 `undefined` 让界面渲染空白,
 * 也不会因为表里写了空串而把合法状态误判成「未知」。
 *
 * <p>空串必须单独挡: `Number('')` 是 `0`, 不拦的话空状态会被解析成「待开售」。
 */
export function resolveSessionStatusLabelKey(status: unknown): string {
  if (typeof status === 'string' && status.trim() === '') return SESSION_STATUS_UNKNOWN_KEY
  const code = typeof status === 'string' || typeof status === 'number' ? Number(status) : NaN
  const key = SESSION_STATUS_LABEL_KEY[code as SessionStatusCode]
  return key || SESSION_STATUS_UNKNOWN_KEY
}

/**
 * el-tag 颜色。
 *
 * <p>保持修复前的既有视觉: 仅「在售」是 success, 其余(含未知)一律 info。
 * 此处刻意不做「按状态分色」的扩展 —— 那是另一个设计决定, 不该混进一次显示缺陷修复里。
 */
export function resolveSessionStatusTagType(status: unknown): 'success' | 'info' {
  return Number(status) === SESSION_STATUS.SELLING ? 'success' : 'info'
}

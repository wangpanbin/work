/**
 * 订单状态 UI 视图模型
 *
 * 单一真理之源:订单5 态的展示/行为契约。
 * 与 backend `cinema-server/.../OrderStatus.java` 一一对应 (int code 是后端唯一标识符).
 *
 * 数据流约定:
 * - 后端 `OrderVO.status: int + statusText: String` — 真实订单展示优先使用后端 statusText;
 * - 本模块的 `labelKey` 仅用于无后端 statusText 的场景(如筛选 tab 标签)。
 * - 两者以**后端为准**;若冲突,后端 statusText 胜出。
 *
 * i18n 迁移(i18n-6):
 * - `label` → `labelKey` (i18n key,模板里 `t(labelKey)`)
 * - `bannerCopy` → `bannerCopyKey`
 * - `BUTTON_BY_ACTION.X.label` → `BUTTON_BY_ACTION.X.labelKey`
 * - 常量文件**保持纯数据结构**,无运行时 i18n 依赖
 */

export const ORDER_STATUS = {
  PENDING_PAY: 0,
  PAID: 1,
  CANCELLED: 2,
  REFUNDING: 3,
  REFUNDED: 4,
} as const

export type OrderStatusCode = (typeof ORDER_STATUS)[keyof typeof ORDER_STATUS]

export const ORDER_STATUS_CODES = [0, 1, 2, 3, 4] as const satisfies readonly OrderStatusCode[]

export type OrderAction = 'pay' | 'cancel' | 'refund' | 'rebook' | 'viewDetail' | 'viewTicket'

export type ElTagType = 'success' | 'warning' | 'info'

export interface OrderStatusView {
  /** i18n key — 模板里 `t(view.labelKey)` 解析 (无后端 statusText 时的兜底) */
  readonly labelKey: string
  /** el-tag type */
  readonly tagType: ElTagType
  /** Payment.vue 顶部状态条文案;空串 = 不显示 */
  readonly bannerCopyKey: string
  /** OrderList 行首红色脉冲点(目前仅 PENDING_PAY) */
  readonly pulse: boolean
  /** 该状态下允许的语义动作(OrderList/Payment 模板据此渲染按钮) */
  readonly availableActions: readonly OrderAction[]
}

export const ORDER_STATUS_VIEW: Record<OrderStatusCode, OrderStatusView> = {
  [ORDER_STATUS.PENDING_PAY]: {
    labelKey: 'order.statusPending',
    tagType: 'warning',
    bannerCopyKey: '',
    pulse: true,
    availableActions: ['pay', 'cancel'],
  },
  [ORDER_STATUS.PAID]: {
    labelKey: 'order.statusPaid',
    tagType: 'success',
    bannerCopyKey: 'order.bannerPaid',
    pulse: false,
    availableActions: ['refund', 'viewDetail', 'viewTicket'],
  },
  [ORDER_STATUS.CANCELLED]: {
    labelKey: 'order.statusCancelled',
    tagType: 'info',
    bannerCopyKey: 'order.bannerCancelled',
    pulse: false,
    availableActions: ['rebook'],
  },
  [ORDER_STATUS.REFUNDING]: {
    labelKey: 'order.statusRefunding',
    tagType: 'warning',
    bannerCopyKey: 'order.bannerRefunding',
    pulse: false,
    availableActions: [],
  },
  [ORDER_STATUS.REFUNDED]: {
    labelKey: 'order.statusRefunded',
    tagType: 'info',
    bannerCopyKey: 'order.bannerRefunded',
    pulse: false,
    availableActions: ['rebook'],
  },
}

export interface StatusFilter {
  readonly code: OrderStatusCode
  readonly labelKey: string
  readonly tagType: ElTagType
}

export const ORDER_STATUS_FILTERS: readonly StatusFilter[] = ORDER_STATUS_CODES.map((code) => ({
  code,
  labelKey: ORDER_STATUS_VIEW[code].labelKey,
  tagType: ORDER_STATUS_VIEW[code].tagType,
}))

export interface ActionButton {
  /** i18n key — 模板里 `t(button.labelKey)` 解析 */
  readonly labelKey: string
  readonly primary?: boolean
}

export const BUTTON_BY_ACTION: Record<OrderAction, ActionButton> = {
  pay:         { labelKey: 'order.actionPay',        primary: true },
  cancel:      { labelKey: 'order.actionCancel' },
  refund:      { labelKey: 'order.actionRefund',     primary: true },
  rebook:      { labelKey: 'order.actionRebook' },
  viewDetail:  { labelKey: 'order.actionViewDetail' },
  viewTicket:  { labelKey: 'order.actionViewTicket' },
}
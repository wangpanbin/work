/**
 * 订单状态 UI 视图模型
 *
 * 单一真理之源:订单5 态的展示/行为契约。
 * 与 backend `cinema-server/.../OrderStatus.java` 一一对应 (int code 是后端唯一标识符).
 *
 * 数据流约定:
 * - 后端 `OrderVO.status: int + statusText: String` — 真实订单展示优先使用后端 statusText;
 * - 本模块的 `label` 仅用于无后端 statusText 的场景(如筛选 tab 标签)。
 * - 两者以**后端为准**;若冲突,后端 statusText 胜出。
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
  /** 中文标签(无后端数据时的兜底) */
  readonly label: string
  /** el-tag type */
  readonly tagType: ElTagType
  /** Payment.vue 顶部状态条文案;空串 = 不显示 */
  readonly bannerCopy: string
  /** OrderList 行首红色脉冲点(目前仅 PENDING_PAY) */
  readonly pulse: boolean
  /** 该状态下允许的语义动作(OrderList/Payment 模板据此渲染按钮) */
  readonly availableActions: readonly OrderAction[]
}

export const ORDER_STATUS_VIEW: Record<OrderStatusCode, OrderStatusView> = {
  [ORDER_STATUS.PENDING_PAY]: {
    label: '待支付',
    tagType: 'warning',
    bannerCopy: '',
    pulse: true,
    availableActions: ['pay', 'cancel'],
  },
  [ORDER_STATUS.PAID]: {
    label: '已支付',
    tagType: 'success',
    bannerCopy: '支付成功,祝您观影愉快!',
    pulse: false,
    availableActions: ['refund', 'viewDetail', 'viewTicket'],
  },
  [ORDER_STATUS.CANCELLED]: {
    label: '已取消',
    tagType: 'info',
    bannerCopy: '订单已取消',
    pulse: false,
    availableActions: ['rebook'],
  },
  [ORDER_STATUS.REFUNDING]: {
    label: '退款中',
    tagType: 'warning',
    bannerCopy: '退款处理中,请稍候…',
    pulse: false,
    availableActions: [],
  },
  [ORDER_STATUS.REFUNDED]: {
    label: '已退款',
    tagType: 'info',
    bannerCopy: '已退款,座位已释放',
    pulse: false,
    availableActions: ['rebook'],
  },
}

export interface StatusFilter {
  readonly code: OrderStatusCode
  readonly label: string
  readonly tagType: ElTagType
}

export const ORDER_STATUS_FILTERS: readonly StatusFilter[] = ORDER_STATUS_CODES.map((code) => ({
  code,
  label: ORDER_STATUS_VIEW[code].label,
  tagType: ORDER_STATUS_VIEW[code].tagType,
}))

export interface ActionButton {
  readonly label: string
  readonly primary?: boolean
}

export const BUTTON_BY_ACTION: Record<OrderAction, ActionButton> = {
  pay:        { label: '去支付',   primary: true },
  cancel:     { label: '取消订单' },
  refund:     { label: '申请退票', primary: true },
  rebook:     { label: '重新选座' },
  viewDetail: { label: '查看详情' },
  viewTicket: { label: '查看电子票' },
}
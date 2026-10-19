import type { ActionCardVO } from '../../types'
import {
  FORBIDDEN_CODE,
  IDEMPOTENT_CONFLICT_CODE,
  ORDER_DISABLED_CODE,
  PROPOSAL_EXPIRED_CODE,
  RATE_LIMIT_CODE,
  SEAT_CONFLICT_CODE,
  UNAUTHORIZED_CODE,
} from '../../api/errorCodes'

/**
 * 对话下单卡片的纯函数模块 (ADR-0004)。
 *
 * <p>**为什么全部下沉成纯函数**: 本仓 vitest 跑在 `environment: 'node'`,
 * 没装 jsdom / `@vue/test-utils`, 挂不上 SFC. 倒计时、过期判定、请求体拼装
 * 这类逻辑如果写在 `.vue` 里就只能靠手点验证 —— 提到这里就能被单测覆盖
 * (见 `tests/components/chat/orderProposalCard.test.ts`)。
 *
 * <p>本模块不 import 任何 Vue / 组件 / 网络 API, 保持纯函数可测。
 */

/** 卡型: 一键下单确认卡 (ADR-0004) */
export const CARD_TYPE_ORDER_PROPOSAL = 'ORDER_PROPOSAL'

/** 卡型: 锁座成功后的「待支付卡」(前端本地生成, 不来自模型) */
export const CARD_TYPE_PENDING_ORDER = 'PENDING_ORDER'

/** 提案状态: 有效 / 已过期 / 不可用(缺 proposalId 或 expiresAt) */
export type ProposalState = 'active' | 'expired' | 'missing'

/** 是否是一键下单确认卡 */
export function isOrderProposalCard(card: ActionCardVO | null | undefined): boolean {
  return !!card && card.type === CARD_TYPE_ORDER_PROPOSAL
}

/** 是否是待支付卡 */
export function isPendingOrderCard(card: ActionCardVO | null | undefined): boolean {
  return !!card && card.type === CARD_TYPE_PENDING_ORDER
}

/**
 * 剩余秒数(向上取整到整秒)。
 *
 * <p>没有 expiresAt / 非有限数 / 已过期, 一律返 0 —— 宁可显示 00:00 让用户
 * 以为过期, 也不要因为一个坏值算出「还有 3 小时」这种误导性的倒计时。
 */
export function remainingSeconds(expiresAt: number | undefined, now: number): number {
  if (typeof expiresAt !== 'number' || !Number.isFinite(expiresAt)) return 0
  const diff = expiresAt - now
  if (diff <= 0) return 0
  return Math.ceil(diff / 1000)
}

/**
 * 提案状态判定。
 *
 * <p>`missing` 与 `expired` 分开: 前者是「这张卡根本不可用」(缺 ID 或缺过期时间,
 * 多半是后端出了意外), 后者是「方案过期了, 重新发起就行」—— 两者给用户的话术不同。
 */
export function proposalState(card: ActionCardVO | null | undefined, now: number): ProposalState {
  if (!isOrderProposalCard(card)) return 'missing'
  const c = card as ActionCardVO
  if (!c.proposalId) return 'missing'
  if (typeof c.expiresAt !== 'number' || !Number.isFinite(c.expiresAt)) return 'missing'
  return c.expiresAt > now ? 'active' : 'expired'
}

/** 秒 → `mm:ss` */
export function formatCountdown(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '00:00'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/**
 * 确认请求体 —— 只有 proposalId。
 *
 * <p>**座位绝不能出现在这里**:座位只在服务端快照里. 前端回传座位等于把
 * ADR-0002 反对的「模型/客户端决定锁哪个座」又放回来了。
 */
export function buildConfirmRequest(card: ActionCardVO | null | undefined): { proposalId: string } | null {
  if (!isOrderProposalCard(card)) return null
  const id = (card as ActionCardVO).proposalId
  if (!id) return null
  return { proposalId: id }
}

/** 重新找一组的请求体 —— 只有场次与张数 */
export function buildRepickRequest(
  card: ActionCardVO | null | undefined,
): { sessionId: string; count: number } | null {
  if (!isOrderProposalCard(card)) return null
  const c = card as ActionCardVO
  if (!c.sessionId) return null
  const count = c.count ?? c.seatIndexes?.length ?? 1
  return { sessionId: c.sessionId, count: Math.max(1, Math.min(4, count)) }
}

/** 支付窗口剩余秒数(与提案倒计时同义, 只是过期时刻来源不同) */
export function payWindowSeconds(orderExpireAt: number | undefined, now: number): number {
  return remainingSeconds(orderExpireAt, now)
}

/** 支付页路由 —— 订单号走 query, 与既有 Payment.vue 的取参方式一致 */
export function buildPayRoute(orderNo: string | undefined): { name: string; query: { orderNo: string } } | null {
  if (!orderNo) return null
  return { name: 'payment', query: { orderNo } }
}

/**
 * 确认失败 → i18n key 的映射(按错误码判断, **不**匹配中文字符串)。
 *
 * <p>spec §5.3 要求按 `err.code` 分支 —— 既有 `SeatSelect.vue` 那种字符串匹配
 * 是历史包袱, 新代码不继承。
 *
 * <p>40003(提案已过期)刻意不在表里: 它不是一条错误文案, 而是**状态迁移** ——
 * 卡片要切到过期态并提示重新发起, 那是 stateHint 的活。走 errorText 会和
 * stateHint 把同一句话说两遍。
 *
 * <p>码的数值归属在 `api/errorCodes.ts`(全站业务码的唯一归属), 这里只放文案。
 */
export const CONFIRM_ERROR_KEYS: Record<number, string> = {
  [SEAT_CONFLICT_CODE]: 'chat.orderProposal.conflictTip',
  [ORDER_DISABLED_CODE]: 'chat.orderProposal.disabledTip',
  [UNAUTHORIZED_CODE]: 'chat.orderProposal.loginRequired',
  [FORBIDDEN_CODE]: 'chat.orderProposal.forbiddenTip',
  [IDEMPOTENT_CONFLICT_CODE]: 'chat.orderProposal.duplicateTip',
  [RATE_LIMIT_CODE]: 'chat.orderProposal.rateLimited',
}

/** 取错误码对应的 i18n key; 不认识 / 缺码返 null(上层退回 err.message) */
export function confirmErrorKey(code: number | undefined): string | null {
  if (typeof code !== 'number' || !Number.isFinite(code)) return null
  const key = CONFIRM_ERROR_KEYS[code]
  return key === undefined ? null : key
}

/** 该错误码是否表示「提案已过期」—— 调用方据此把卡片切到过期态 */
export function isProposalExpiredCode(code: number | undefined): boolean {
  return code === PROPOSAL_EXPIRED_CODE
}

import type { ActionCardVO } from '../../types'
import { isOrderProposalCard, isPendingOrderCard } from './orderProposalCard'

/**
 * 聊天卡片登记表 —— 「这张卡是什么」与「这张卡的稳定 key」的唯一答案。
 *
 * <p>**为什么要有它**:
 * <ul>
 *   <li>卡型判定原先只有两个 `isXxxCard` 谓词, 但「卡有哪几种」这个知识散在
 *       谓词、组件文件名、模板的 v-if 链三处 —— 加一种卡要改三个地方。</li>
 *   <li>v-for 的 key 表达式 {@code card.cardId ?? `${card.sessionId}-${idx}`} 此前
 *       直接写在模板里, 没有任何测试能覆盖它。它是**稳定 key**的硬要求:
 *       装配器丢卡后索引会位移, 用索引做 key 会让 Vue 复用错 DOM(把上一张卡的内容
 *       留在新卡的位置上)。</li>
 * </ul>
 *
 * <p>本模块是纯函数, 不 import 任何组件 —— 可直接单测。
 */

/** 卡型。 */
export type CardKind = 'orderProposal' | 'pendingOrder' | 'action'

/** 全部卡型 —— 渲染顺序与模板的 v-if 链一致。 */
export const CARD_KINDS: readonly CardKind[] = ['orderProposal', 'pendingOrder', 'action']

/** 这张卡是哪一种。未知卡型一律按 {@link CardKind} 的 'action' 兜底(通用建议卡)。 */
export function cardKindOf(card: ActionCardVO | null | undefined): CardKind {
  if (isOrderProposalCard(card)) return 'orderProposal'
  if (isPendingOrderCard(card)) return 'pendingOrder'
  return 'action'
}

/**
 * 卡片的稳定 key。
 *
 * <p>优先用服务端给的 `cardId`; 没有时退回 `场次-索引`。
 * **不能只用索引** —— 装配器丢卡后索引会位移, 用索引做 key 会让 Vue 复用错 DOM。
 *
 * <p>语义与模板里原先的 {@code card.cardId ?? `${card.sessionId}-${idx}`} 逐字一致
 * (`??` 只在 null/undefined 时兜底, 空串照原样返回)。
 */
export function cardKey(card: ActionCardVO, idx: number): string {
  return card.cardId ?? `${card.sessionId}-${idx}`
}

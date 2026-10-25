import type { ActionCardVO } from '../../types'
import { isOrderGuideCard, isOrderProposalCard, isPendingOrderCard } from './orderProposalCard'

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
export type CardKind = 'orderProposal' | 'pendingOrder' | 'orderGuide' | 'action'

/** 全部卡型 —— 渲染顺序与模板的 v-if 链一致。 */
export const CARD_KINDS: readonly CardKind[] = ['orderProposal', 'pendingOrder', 'orderGuide', 'action']

/** 这张卡是哪一种。未知卡型一律按 {@link CardKind} 的 'action' 兜底(通用建议卡)。 */
export function cardKindOf(card: ActionCardVO | null | undefined): CardKind {
  if (isOrderProposalCard(card)) return 'orderProposal'
  if (isPendingOrderCard(card)) return 'pendingOrder'
  if (isOrderGuideCard(card)) return 'orderGuide'
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

/**
 * 服务端卡型枚举 → i18n 文案 key。
 *
 * <p><b>为什么需要它</b>(2026-10-24): 模板原先直接渲染 {@code card.type},
 * 于是服务端枚举(SEAT_SUGGESTION / ORDER_PROPOSAL / …)原样出现在界面上 ——
 * 英文界面里也会露出英文枚举, 而相邻的每一句文案都是翻译过的, 夹在中间很突兀。
 *
 * <p>未知卡型返回 {@code null}, 调用方回退到原样显示 —— 新增卡型时忘了登记文案,
 * 最多是「显示英文枚举」, 而不是空白或报错。
 */
const CARD_TYPE_LABEL_KEYS: Readonly<Record<string, string>> = {
  SEAT_SUGGESTION: 'chat.cardTypeSeatSuggestion',
  ORDER_PROPOSAL: 'chat.cardTypeOrderProposal',
  PENDING_ORDER: 'chat.cardTypePendingOrder',
  ORDER_GUIDE: 'chat.cardTypeOrderGuide',
}

/** 卡型枚举对应的 i18n key; 未知卡型返回 null。 */
export function cardTypeLabelKey(type: string | null | undefined): string | null {
  if (!type) return null
  return CARD_TYPE_LABEL_KEYS[type] ?? null
}

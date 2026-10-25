import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { CARD_KINDS, cardKey, cardKindOf, cardTypeLabelKey } from '../../../src/components/chat/cardRegistry'
import {
  CARD_TYPE_ORDER_PROPOSAL,
  CARD_TYPE_PENDING_ORDER,
} from '../../../src/components/chat/orderProposalCard'
import type { ActionCardVO } from '../../../src/types'
import zhCN from '../../../src/i18n/locales/zh-CN'
import enUS from '../../../src/i18n/locales/en-US'

/**
 * 聊天卡片登记表的契约。
 *
 * <p>重点在 `cardKey` —— 它是 v-for 的**稳定 key**:
 * 装配器丢卡后索引会位移, 用索引做 key 会让 Vue 复用错 DOM(把上一张卡的内容
 * 留在新卡的位置上)。原先这个表达式直接写在模板里, 没有任何测试能覆盖。
 */
describe('cardKindOf — 卡型识别', () => {
  const base: ActionCardVO = { type: 'ACTION', sessionId: '1001' }

  it('一键下单确认卡', () => {
    expect(cardKindOf({ ...base, type: CARD_TYPE_ORDER_PROPOSAL })).toBe('orderProposal')
  })

  it('待支付卡', () => {
    expect(cardKindOf({ ...base, type: CARD_TYPE_PENDING_ORDER })).toBe('pendingOrder')
  })

  it('其余一律按通用建议卡兜底', () => {
    expect(cardKindOf({ ...base, type: 'SOMETHING_ELSE' })).toBe('action')
    expect(cardKindOf(base)).toBe('action')
  })

  it('空卡不炸', () => {
    expect(cardKindOf(null)).toBe('action')
    expect(cardKindOf(undefined)).toBe('action')
  })

  it('卡型清单与渲染顺序一致', () => {
    expect(CARD_KINDS).toEqual(['orderProposal', 'pendingOrder', 'action'])
  })
})

describe('cardKey — 稳定 key', () => {
  const base: ActionCardVO = { type: 'ACTION', sessionId: '1001' }

  it('有 cardId → 用 cardId(与索引无关, 丢卡不位移)', () => {
    expect(cardKey({ ...base, cardId: 'abc-1' }, 0)).toBe('abc-1')
    expect(cardKey({ ...base, cardId: 'abc-1' }, 7)).toBe('abc-1')
  })

  it('没有 cardId → 退回「场次-索引」', () => {
    expect(cardKey(base, 3)).toBe('1001-3')
  })

  it('cardId 为空串时照原样返回(?? 只兜 null/undefined)', () => {
    expect(cardKey({ ...base, cardId: '' }, 1)).toBe('')
  })

  it('语义与原模板表达式逐字一致', () => {
    const card = { ...base, cardId: 'x' }
    expect(cardKey(card, 2)).toBe(card.cardId ?? `${card.sessionId}-${2}`)
    const noId = { ...base }
    expect(cardKey(noId, 2)).toBe(noId.cardId ?? `${noId.sessionId}-${2}`)
  })
})

/**
 * 静态契约 —— ChatMessage.vue 必须用 `cardKey`, 不许把表达式又写回模板。
 */
describe('ChatMessage.vue — 走 cardRegistry', () => {
  const src = readFileSync(
    fileURLToPath(new URL('../../../src/components/chat/ChatMessage.vue', import.meta.url)),
    'utf-8',
  )

  it('引用了 cardRegistry', () => {
    expect(src).toContain("from './cardRegistry'")
  })

  it('v-for 的 key 用 cardKey(card, idx)', () => {
    expect(src).toContain(':key="cardKey(card, idx)"')
  })

  it('模板里不再出现内联的 cardId ?? sessionId-idx 表达式', () => {
    expect(src).not.toContain('card.cardId ??')
  })
})

/**
 * 卡型文案走 i18n(2026-10-24)。
 *
 * 模板原先直接渲染 `card.type`, 于是服务端枚举(SEAT_SUGGESTION / ORDER_PROPOSAL / …)
 * 原样出现在界面上 —— 英文界面里也会露出英文枚举, 而相邻的每一句文案都是翻译过的。
 */
describe('cardTypeLabelKey — 卡型枚举 → i18n key', () => {
  it('登记过的卡型返回对应 key', () => {
    expect(cardTypeLabelKey('SEAT_SUGGESTION')).toBe('chat.cardTypeSeatSuggestion')
    expect(cardTypeLabelKey('ORDER_PROPOSAL')).toBe('chat.cardTypeOrderProposal')
    expect(cardTypeLabelKey('PENDING_ORDER')).toBe('chat.cardTypePendingOrder')
  })

  it('未知卡型返回 null(调用方回退到原样显示, 不空白)', () => {
    expect(cardTypeLabelKey('SOMETHING_NEW')).toBeNull()
  })

  it('空值返回 null', () => {
    expect(cardTypeLabelKey(null)).toBeNull()
    expect(cardTypeLabelKey(undefined)).toBeNull()
    expect(cardTypeLabelKey('')).toBeNull()
  })

  it('返回的 key 必须在 zh-CN 与 en-US 两份 locale 里都存在', () => {
    // 静态导入两份 locale, 直接查表 —— 避免 key 拼错时界面显示成裸 key
    // key 形如 'chat.cardTypeSeatSuggestion', 按点号路径逐级下钻
    const lookup = (root: unknown, dotted: string) =>
      dotted.split('.').reduce<unknown>((acc, seg) => {
        if (acc == null || typeof acc !== 'object') return undefined
        return (acc as Record<string, unknown>)[seg]
      }, root)

    for (const type of ['SEAT_SUGGESTION', 'ORDER_PROPOSAL', 'PENDING_ORDER']) {
      const key = cardTypeLabelKey(type)
      expect(key).not.toBeNull()
      expect(lookup(zhCN, key as string), `zh-CN 缺 ${key}`).toBeTruthy()
      expect(lookup(enUS, key as string), `en-US 缺 ${key}`).toBeTruthy()
    }
  })

  it('ActionCard.vue 走 cardTypeLabelKey, 不直接渲染原始枚举', () => {
    const src = readFileSync(
      fileURLToPath(new URL('../../../src/components/chat/ActionCard.vue', import.meta.url)),
      'utf-8',
    )
    expect(src).toContain("from './cardRegistry'")
    expect(src).toContain('cardTypeLabelKey(')
    // 模板里不得直接输出 card.type
    expect(src).not.toMatch(/\{\{\s*card\.type\s*\}\}/)
  })
})

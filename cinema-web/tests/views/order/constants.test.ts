import { describe, it, expect } from 'vitest'
import {
  ORDER_STATUS,
  ORDER_STATUS_CODES,
  ORDER_STATUS_VIEW,
  ORDER_STATUS_FILTERS,
  BUTTON_BY_ACTION,
  type OrderStatusCode,
  type OrderAction,
} from '../../../src/views/order/constants'

describe('OrderStatus — foundation', () => {
  it('ORDER_STATUS has 5 entries with codes 0–4', () => {
    expect(ORDER_STATUS).toEqual({
      PENDING_PAY: 0,
      PAID: 1,
      CANCELLED: 2,
      REFUNDING: 3,
      REFUNDED: 4,
    })
  })

  it('ORDER_STATUS_CODES equals [0,1,2,3,4]', () => {
    expect([...ORDER_STATUS_CODES]).toEqual([0, 1, 2, 3, 4])
  })

  it('ORDER_STATUS_VIEW is a Record covering every code (TS compile-time + runtime)', () => {
    const keys = Object.keys(ORDER_STATUS_VIEW).map(Number).sort((a, b) => a - b)
    expect(keys).toEqual([0, 1, 2, 3, 4])
  })

  it('every view has the 5 documented fields', () => {
    for (const code of ORDER_STATUS_CODES) {
      const v = ORDER_STATUS_VIEW[code]
      expect(typeof v.label).toBe('string')
      expect(['success', 'warning', 'info']).toContain(v.tagType)
      expect(typeof v.bannerCopy).toBe('string')
      expect(typeof v.pulse).toBe('boolean')
      expect(Array.isArray(v.availableActions)).toBe(true)
    }
  })
})

describe('OrderStatus — per-state content (verbatim against current UI)', () => {
  it('PENDING_PAY (0): 待支付 · warning · pulse · pay+cancel', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.PENDING_PAY]
    expect(v.label).toBe('待支付')
    expect(v.tagType).toBe('warning')
    expect(v.bannerCopy).toBe('')
    expect(v.pulse).toBe(true)
    expect(v.availableActions).toEqual(['pay', 'cancel'])
  })

  it('PAID (1): 已支付 · success · banner copy · refund/viewDetail/viewTicket', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.PAID]
    expect(v.label).toBe('已支付')
    expect(v.tagType).toBe('success')
    expect(v.bannerCopy).toBe('支付成功,祝您观影愉快!')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual(['refund', 'viewDetail', 'viewTicket'])
  })

  it('CANCELLED (2): 已取消 · info · banner · only rebook', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.CANCELLED]
    expect(v.label).toBe('已取消')
    expect(v.tagType).toBe('info')
    expect(v.bannerCopy).toBe('订单已取消')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual(['rebook'])
  })

  it('REFUNDING (3): 退款中 · warning · banner · no actions', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.REFUNDING]
    expect(v.label).toBe('退款中')
    expect(v.tagType).toBe('warning')
    expect(v.bannerCopy).toBe('退款处理中,请稍候…')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual([])
  })

  it('REFUNDED (4): 已退款 · info · banner · only rebook', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.REFUNDED]
    expect(v.label).toBe('已退款')
    expect(v.tagType).toBe('info')
    expect(v.bannerCopy).toBe('已退款,座位已释放')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual(['rebook'])
  })
})

describe('OrderStatus — ORDER_STATUS_FILTERS derivation', () => {
  it('has 5 entries in code order', () => {
    expect(ORDER_STATUS_FILTERS).toHaveLength(5)
    expect(ORDER_STATUS_FILTERS.map((f) => f.code)).toEqual([0, 1, 2, 3, 4])
  })

  it('every filter entry mirrors VIEW (code/label/tagType)', () => {
    for (const f of ORDER_STATUS_FILTERS) {
      const v = ORDER_STATUS_VIEW[f.code as OrderStatusCode]
      expect(f.label).toBe(v.label)
      expect(f.tagType).toBe(v.tagType)
    }
  })
})

describe('OrderStatus — BUTTON_BY_ACTION', () => {
  it('covers all 6 action types', () => {
    const keys = Object.keys(BUTTON_BY_ACTION).sort()
    expect(keys).toEqual(['cancel', 'pay', 'rebook', 'refund', 'viewDetail', 'viewTicket'])
  })

  it('every button has a non-empty label', () => {
    for (const k of Object.keys(BUTTON_BY_ACTION) as OrderAction[]) {
      expect(BUTTON_BY_ACTION[k].label.length).toBeGreaterThan(0)
    }
  })
})

describe('OrderStatus — cross-validation (round-trip)', () => {
  it('no orphan action: every action is used by at least one status', () => {
    const used = new Set<OrderAction>()
    for (const code of ORDER_STATUS_CODES) {
      for (const a of ORDER_STATUS_VIEW[code].availableActions) {
        used.add(a)
      }
    }
    for (const k of Object.keys(BUTTON_BY_ACTION) as OrderAction[]) {
      expect(used.has(k)).toBe(true)
    }
  })

  it('no dangling reference: every status action exists in BUTTON_BY_ACTION', () => {
    const declared = new Set(Object.keys(BUTTON_BY_ACTION))
    for (const code of ORDER_STATUS_CODES) {
      for (const a of ORDER_STATUS_VIEW[code].availableActions) {
        expect(declared.has(a)).toBe(true)
      }
    }
  })
})
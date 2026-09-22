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

/**
 * constants.test.ts — OrderStatus 视图模型契约锁
 *
 * i18n-6 迁移后:
 * - `label` (硬编码中文) → `labelKey` (i18n key 字符串)
 * - `bannerCopy` → `bannerCopyKey`
 * - `BUTTON_BY_ACTION.X.label` → `BUTTON_BY_ACTION.X.labelKey`
 * - 测试断言改为: key 字符串是稳定的 wire-format (i18n dictionary 的契约)
 *
 * 这层单测保证: 不管中英字典内容怎么改, status→labelKey 的映射不能漂移
 * (如果漂移,前端 OrderList/Payment 就会显示错乱状态)
 */

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

  it('every view has the 5 documented fields (labelKey/bannerCopyKey/tagType/pulse/availableActions)', () => {
    for (const code of ORDER_STATUS_CODES) {
      const v = ORDER_STATUS_VIEW[code]
      expect(typeof v.labelKey).toBe('string')
      expect(v.labelKey).toMatch(/^order\.status[A-Z]/)   // 锁定命名空间
      expect(['success', 'warning', 'info']).toContain(v.tagType)
      expect(typeof v.bannerCopyKey).toBe('string')
      expect(typeof v.pulse).toBe('boolean')
      expect(Array.isArray(v.availableActions)).toBe(true)
    }
  })
})

describe('OrderStatus — per-state content (wire-format lock)', () => {
  it('PENDING_PAY (0): order.statusPending · warning · pulse · pay+cancel', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.PENDING_PAY]
    expect(v.labelKey).toBe('order.statusPending')
    expect(v.tagType).toBe('warning')
    expect(v.bannerCopyKey).toBe('')
    expect(v.pulse).toBe(true)
    expect(v.availableActions).toEqual(['pay', 'cancel'])
  })

  it('PAID (1): order.statusPaid · success · banner copy · refund/viewDetail/viewTicket', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.PAID]
    expect(v.labelKey).toBe('order.statusPaid')
    expect(v.tagType).toBe('success')
    expect(v.bannerCopyKey).toBe('order.bannerPaid')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual(['refund', 'viewDetail', 'viewTicket'])
  })

  it('CANCELLED (2): order.statusCancelled · info · banner copy · rebook', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.CANCELLED]
    expect(v.labelKey).toBe('order.statusCancelled')
    expect(v.tagType).toBe('info')
    expect(v.bannerCopyKey).toBe('order.bannerCancelled')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual(['rebook'])
  })

  it('REFUNDING (3): order.statusRefunding · warning · banner copy · (no actions)', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.REFUNDING]
    expect(v.labelKey).toBe('order.statusRefunding')
    expect(v.tagType).toBe('warning')
    expect(v.bannerCopyKey).toBe('order.bannerRefunding')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual([])
  })

  it('REFUNDED (4): order.statusRefunded · info · banner copy · rebook', () => {
    const v = ORDER_STATUS_VIEW[ORDER_STATUS.REFUNDED]
    expect(v.labelKey).toBe('order.statusRefunded')
    expect(v.tagType).toBe('info')
    expect(v.bannerCopyKey).toBe('order.bannerRefunded')
    expect(v.pulse).toBe(false)
    expect(v.availableActions).toEqual(['rebook'])
  })

  it('round-trip: status code ↔ labelKey (覆盖全 5 态)', () => {
    for (const code of ORDER_STATUS_CODES) {
      const v = ORDER_STATUS_VIEW[code]
      const back: OrderStatusCode | undefined = ORDER_STATUS_CODES.find(
        (c) => ORDER_STATUS_VIEW[c].labelKey === v.labelKey,
      )
      expect(back).toBe(code)
    }
  })
})

describe('OrderStatus — filters (wire-format)', () => {
  it('FILTERS 长度等于状态数(5)', () => {
    expect(ORDER_STATUS_FILTERS.length).toBe(5)
  })

  it('FILTERS 第一项 PENDING_PAY.labelKey 等于 status view 的 labelKey', () => {
    expect(ORDER_STATUS_FILTERS[0].code).toBe(ORDER_STATUS.PENDING_PAY)
    expect(ORDER_STATUS_FILTERS[0].labelKey).toBe('order.statusPending')
  })

  it('FILTERS 顺序与 ORDER_STATUS_CODES 一致', () => {
    const codes = ORDER_STATUS_FILTERS.map((f) => f.code)
    expect([...codes]).toEqual([...ORDER_STATUS_CODES])
  })
})

describe('OrderAction — button labelKey (wire-format)', () => {
  it('pay/cancel/refund/rebook/viewDetail/viewTicket 都有 labelKey', () => {
    const actions: OrderAction[] = ['pay', 'cancel', 'refund', 'rebook', 'viewDetail', 'viewTicket']
    for (const a of actions) {
      expect(typeof BUTTON_BY_ACTION[a].labelKey).toBe('string')
      expect(BUTTON_BY_ACTION[a].labelKey).toMatch(/^order\.action[A-Z]/)
    }
  })

  it('pay 与 refund 标记为 primary', () => {
    expect(BUTTON_BY_ACTION.pay.primary).toBe(true)
    expect(BUTTON_BY_ACTION.refund.primary).toBe(true)
  })

  it('cancel / rebook / viewDetail / viewTicket 不标记 primary', () => {
    expect(BUTTON_BY_ACTION.cancel.primary).toBeUndefined()
    expect(BUTTON_BY_ACTION.rebook.primary).toBeUndefined()
    expect(BUTTON_BY_ACTION.viewDetail.primary).toBeUndefined()
    expect(BUTTON_BY_ACTION.viewTicket.primary).toBeUndefined()
  })
})
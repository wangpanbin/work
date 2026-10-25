import { describe, it, expect } from 'vitest'
import {
  CARD_TYPE_ORDER_GUIDE,
  CARD_TYPE_ORDER_PROPOSAL,
  CARD_TYPE_PENDING_ORDER,
  CONFIRM_ERROR_KEYS,
  buildConfirmRequest,
  buildGuideRoute,
  buildPayRoute,
  buildRepickRequest,
  confirmErrorKey,
  formatCountdown,
  isOrderGuideCard,
  isOrderProposalCard,
  isPendingOrderCard,
  isProposalExpiredCode,
  payWindowSeconds,
  proposalState,
  remainingSeconds,
} from '../../../src/components/chat/orderProposalCard'
import type { ActionCardVO } from '../../../src/types'
import zhCN from '../../../src/i18n/locales/zh-CN'
import enUS from '../../../src/i18n/locales/en-US'

/**
 * orderProposalCard 纯函数测试 (ADR-0004)。
 *
 * <p>**为什么必须测这里**: 本仓 vitest 跑在 `environment: 'node'`, 没装 jsdom /
 * `@vue/test-utils`, 挂不上 SFC —— 倒计时/过期判定/请求体拼装如果只写在 `.vue` 里
 * 就只能靠手点验证. 这些逻辑提到纯函数层才有自动化闸门。
 *
 * <p>重点锁三条契约:
 * <ol>
 *   <li><b>确认请求体里不能有座位</b> —— 座位只在服务端快照里</li>
 *   <li><b>过期必须与「不可用」区分</b> —— 两者给用户的话术不同</li>
 *   <li><b>坏值一律退化成 0 秒</b>, 宁可显示已过期也不能显示一个误导性的长倒计时</li>
 * </ol>
 */

const NOW = 1_800_000_000_000

/** 按 dot-path 取字典里的值(取不到返 undefined) */
function resolve(obj: Record<string, unknown>, path: string): unknown {
  return path.split('.').reduce<unknown>((acc, k) => (acc as Record<string, unknown>)?.[k], obj)
}

function proposalCard(overrides: Partial<ActionCardVO> = {}): ActionCardVO {
  return {
    type: CARD_TYPE_ORDER_PROPOSAL,
    sessionId: '1001',
    count: 2,
    seatIndexes: [40, 41],
    seatDesc: '4排5座、4排6座',
    totalAmount: 90,
    proposalId: 'P-1',
    expiresAt: NOW + 90_000,
    ...overrides,
  }
}

describe('isOrderProposalCard / isPendingOrderCard', () => {
  it('按 type 判定卡型', () => {
    expect(isOrderProposalCard(proposalCard())).toBe(true)
    expect(isOrderProposalCard({ ...proposalCard(), type: 'SEAT_SUGGESTION' })).toBe(false)
    expect(isPendingOrderCard({ ...proposalCard(), type: CARD_TYPE_PENDING_ORDER })).toBe(true)
    expect(isPendingOrderCard(proposalCard())).toBe(false)
  })

  it('null / undefined 一律 false, 不抛错', () => {
    expect(isOrderProposalCard(null)).toBe(false)
    expect(isOrderProposalCard(undefined)).toBe(false)
    expect(isPendingOrderCard(null)).toBe(false)
    expect(isPendingOrderCard(undefined)).toBe(false)
  })
})

describe('remainingSeconds', () => {
  it('未过期 → 剩余秒数(向上取整到整秒)', () => {
    expect(remainingSeconds(NOW + 90_000, NOW)).toBe(90)
    expect(remainingSeconds(NOW + 1_400, NOW)).toBe(2)
  })

  it('刚好到期 / 已过期 → 0', () => {
    expect(remainingSeconds(NOW, NOW)).toBe(0)
    expect(remainingSeconds(NOW - 1, NOW)).toBe(0)
  })

  it('expiresAt 缺失或非法 → 0(宁可显示已过期, 也不显示误导性的长倒计时)', () => {
    expect(remainingSeconds(undefined, NOW)).toBe(0)
    expect(remainingSeconds(Number.NaN, NOW)).toBe(0)
    expect(remainingSeconds(Number.POSITIVE_INFINITY, NOW)).toBe(0)
  })
})

describe('proposalState', () => {
  it('未过期 → active', () => {
    expect(proposalState(proposalCard(), NOW)).toBe('active')
  })

  it('已过期 → expired', () => {
    expect(proposalState(proposalCard({ expiresAt: NOW - 1 }), NOW)).toBe('expired')
  })

  it('缺 proposalId / 缺 expiresAt / 不是该卡型 → missing', () => {
    expect(proposalState(proposalCard({ proposalId: undefined }), NOW)).toBe('missing')
    expect(proposalState(proposalCard({ expiresAt: undefined }), NOW)).toBe('missing')
    expect(proposalState({ ...proposalCard(), type: 'SEAT_SUGGESTION' }, NOW)).toBe('missing')
    expect(proposalState(null, NOW)).toBe('missing')
  })
})

describe('formatCountdown', () => {
  it('秒 → mm:ss', () => {
    expect(formatCountdown(90)).toBe('01:30')
    expect(formatCountdown(59)).toBe('00:59')
    expect(formatCountdown(0)).toBe('00:00')
  })

  it('非法 / 负数 → 00:00', () => {
    expect(formatCountdown(-5)).toBe('00:00')
    expect(formatCountdown(Number.NaN)).toBe('00:00')
  })
})

describe('buildConfirmRequest', () => {
  it('只有 proposalId —— 座位绝不能出现在请求体里', () => {
    expect(buildConfirmRequest(proposalCard())).toEqual({ proposalId: 'P-1' })
  })

  it('缺 proposalId / 不是该卡型 → null', () => {
    expect(buildConfirmRequest(proposalCard({ proposalId: undefined }))).toBeNull()
    expect(buildConfirmRequest({ ...proposalCard(), type: 'SEAT_SUGGESTION' })).toBeNull()
    expect(buildConfirmRequest(null)).toBeNull()
  })
})

describe('buildRepickRequest', () => {
  it('带场次与张数', () => {
    expect(buildRepickRequest(proposalCard())).toEqual({ sessionId: '1001', count: 2 })
  })

  it('缺 count 时用座位数兜底, 并钳制到 [1,4]', () => {
    expect(buildRepickRequest(proposalCard({ count: undefined }))).toEqual({ sessionId: '1001', count: 2 })
    // count 越界 → 夹到 4(卡上的 count 由服务端写, 正常不会越界, 这里是防御)
    expect(buildRepickRequest(proposalCard({ count: 99, seatIndexes: [1, 2] }))).toEqual({
      sessionId: '1001',
      count: 4,
    })
    expect(buildRepickRequest(proposalCard({ count: 0, seatIndexes: [1, 2] }))).toEqual({
      sessionId: '1001',
      count: 1,
    })
  })

  it('缺 sessionId / 不是该卡型 → null', () => {
    expect(buildRepickRequest(proposalCard({ sessionId: '' }))).toBeNull()
    expect(buildRepickRequest({ ...proposalCard(), type: 'SEAT_SUGGESTION' })).toBeNull()
  })
})

describe('payWindowSeconds', () => {
  it('与提案倒计时同义, 只是过期时刻来源不同', () => {
    expect(payWindowSeconds(NOW + 15 * 60_000, NOW)).toBe(900)
    expect(payWindowSeconds(NOW - 1, NOW)).toBe(0)
    expect(payWindowSeconds(undefined, NOW)).toBe(0)
  })
})

describe('buildPayRoute', () => {
  it('订单号走 query, 与既有 Payment.vue 的取参方式一致', () => {
    expect(buildPayRoute('ORD-9')).toEqual({ name: 'payment', query: { orderNo: 'ORD-9' } })
  })

  it('没有订单号 → null', () => {
    expect(buildPayRoute(undefined)).toBeNull()
    expect(buildPayRoute('')).toBeNull()
  })
})

describe('confirmErrorKey / isProposalExpiredCode', () => {
  it('按错误码给出 i18n key(按 code 分支, 不靠中文字符串匹配)', () => {
    expect(confirmErrorKey(40002)).toBe('chat.orderProposal.conflictTip')
    expect(confirmErrorKey(40004)).toBe('chat.orderProposal.disabledTip')
    expect(confirmErrorKey(40101)).toBe('chat.orderProposal.loginRequired')
    expect(confirmErrorKey(40301)).toBe('chat.orderProposal.forbiddenTip')
    expect(confirmErrorKey(40900)).toBe('chat.orderProposal.duplicateTip')
    expect(confirmErrorKey(42900)).toBe('chat.orderProposal.rateLimited')
  })

  it('未知码 / 缺码 / NaN → null(上层退回 err.message)', () => {
    expect(confirmErrorKey(undefined)).toBeNull()
    expect(confirmErrorKey(50000)).toBeNull()
    expect(confirmErrorKey(Number.NaN)).toBeNull()
  })

  it('40003 走状态迁移(切过期态)而不是错误文案 —— 不在文案表里', () => {
    expect(isProposalExpiredCode(40003)).toBe(true)
    expect(confirmErrorKey(40003)).toBeNull()
    expect(isProposalExpiredCode(40002)).toBe(false)
    expect(isProposalExpiredCode(undefined)).toBe(false)
  })

  it('错误文案表里的每个 key 在 zh-CN 与 en-US 都真实存在', () => {
    const missingZh: string[] = []
    const missingEn: string[] = []
    for (const key of Object.values(CONFIRM_ERROR_KEYS)) {
      if (resolve(zhCN as Record<string, unknown>, key) === undefined) missingZh.push(key)
      if (resolve(enUS as Record<string, unknown>, key) === undefined) missingEn.push(key)
    }
    expect(missingZh, `zh-CN ，缺失: ${missingZh.join(', ')}`).toEqual([])
    expect(missingEn, `en-US ，缺失: ${missingEn.join(', ')}`).toEqual([])
  })
})

describe('引导卡(取消/退票)—— 只引导, 助手不执行(ADR-0002)', () => {
  const base: ActionCardVO = { type: CARD_TYPE_ORDER_GUIDE, sessionId: '1001' }

  it('识别引导卡', () => {
    expect(isOrderGuideCard(base)).toBe(true)
    expect(isOrderGuideCard({ ...base, type: CARD_TYPE_ORDER_PROPOSAL })).toBe(false)
    expect(isOrderGuideCard(null)).toBe(false)
  })

  it('目标路由固定是订单页', () => {
    expect(buildGuideRoute(base)).toBe('/orders')
    expect(buildGuideRoute({ ...base, guideRoute: '/orders' })).toBe('/orders')
  })

  it('路由必须是站内相对路径(防外链注入)', () => {
    expect(buildGuideRoute({ ...base, guideRoute: 'https://evil.example' })).toBe('/orders')
    expect(buildGuideRoute({ ...base, guideRoute: '//evil.example' })).toBe('/orders')
  })

  it('非引导卡一律退回订单页', () => {
    expect(buildGuideRoute({ ...base, type: CARD_TYPE_ORDER_PROPOSAL })).toBe('/orders')
    expect(buildGuideRoute(null)).toBe('/orders')
  })
})

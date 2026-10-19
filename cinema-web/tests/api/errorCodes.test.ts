import { describe, it, expect } from 'vitest'
import {
  BAD_REQUEST_CODE,
  FORBIDDEN_CODE,
  IDEMPOTENT_CONFLICT_CODE,
  ORDER_DISABLED_CODE,
  PROPOSAL_EXPIRED_CODE,
  RATE_LIMIT_CODE,
  SEAT_CONFLICT_CODE,
  SYSTEM_ERROR_CODE,
  TOKEN_EXPIRED_CODE,
  UNAUTHORIZED_CODE,
  isApiError,
  isSeatConflict,
  seatConflictOf,
  type ApiError,
} from '../../src/api/errorCodes'

/**
 * 业务错误码契约单测。
 *
 * <p>**为什么锁「码 + 载荷」这个组合**: 锁座冲突过去靠中文字面量识别
 * (`msg.includes('座位已被占用')`), 后端改一次文案就静默失效。
 * 而只看 40002 也不行 —— 它是 `BizException(String)` 的默认业务码,
 * 「订单不存在 / 场次不存在 / 场次已开场」全都是 40002。
 * 真正唯一的判别是 **码 + `data.conflict` 载荷**。
 *
 * <p>因此本文件的断言矩阵是本仓里唯一一处「什么才算锁座冲突」的定义。
 */

function apiError(code?: number, data?: unknown, message = 'boom'): ApiError {
  const e = new Error(message) as ApiError
  if (code !== undefined) e.code = code
  if (data !== undefined) e.data = data
  return e
}

describe('业务错误码常量', () => {
  it('与后端 ResultCode 一致', () => {
    expect(BAD_REQUEST_CODE).toBe(40001)
    expect(SEAT_CONFLICT_CODE).toBe(40002)
    expect(PROPOSAL_EXPIRED_CODE).toBe(40003)
    expect(ORDER_DISABLED_CODE).toBe(40004)
    expect(UNAUTHORIZED_CODE).toBe(40101)
    expect(TOKEN_EXPIRED_CODE).toBe(40102)
    expect(FORBIDDEN_CODE).toBe(40301)
    expect(IDEMPOTENT_CONFLICT_CODE).toBe(40900)
    expect(RATE_LIMIT_CODE).toBe(42900)
    expect(SYSTEM_ERROR_CODE).toBe(50000)
  })

  it('两码不等的那些互不相等(避免分支串味)', () => {
    expect(SEAT_CONFLICT_CODE).not.toBe(PROPOSAL_EXPIRED_CODE)
    expect(SEAT_CONFLICT_CODE).not.toBe(ORDER_DISABLED_CODE)
    expect(UNAUTHORIZED_CODE).not.toBe(TOKEN_EXPIRED_CODE)
  })
})

describe('isApiError', () => {
  it('带数字业务码 → true', () => {
    expect(isApiError(apiError(SEAT_CONFLICT_CODE))).toBe(true)
  })

  it('无业务码(网络层错误) → false', () => {
    expect(isApiError(new Error('Network Error'))).toBe(false)
  })

  it('非 Error → false', () => {
    expect(isApiError(null)).toBe(false)
    expect(isApiError(undefined)).toBe(false)
    expect(isApiError({ code: 40002 })).toBe(false)
  })
})

describe('isSeatConflict —— 锁座冲突判定矩阵', () => {
  it('码对 + 载荷是数组 → 冲突', () => {
    expect(isSeatConflict(apiError(SEAT_CONFLICT_CODE, { conflict: [1, 2] }))).toBe(true)
  })

  it('码对但载荷为 null → 不是冲突(40002 是通用业务码)', () => {
    expect(isSeatConflict(apiError(SEAT_CONFLICT_CODE, null))).toBe(false)
  })

  it('码对但缺载荷 → 不是冲突', () => {
    expect(isSeatConflict(apiError(SEAT_CONFLICT_CODE))).toBe(false)
  })

  it('码不对 → 不是冲突', () => {
    expect(isSeatConflict(apiError(BAD_REQUEST_CODE, { conflict: [1, 2] }))).toBe(false)
    expect(isSeatConflict(apiError(PROPOSAL_EXPIRED_CODE, { conflict: [1, 2] }))).toBe(false)
  })

  it('载荷类型不对 → 不是冲突', () => {
    expect(isSeatConflict(apiError(SEAT_CONFLICT_CODE, { conflict: 'x' }))).toBe(false)
    expect(isSeatConflict(apiError(SEAT_CONFLICT_CODE, { conflict: 42 }))).toBe(false)
  })

  it('空数组仍然是冲突(是锁座冲突, 只是没列出座位)', () => {
    expect(isSeatConflict(apiError(SEAT_CONFLICT_CODE, { conflict: [] }))).toBe(true)
  })

  it('中文消息完全不影响判定(后端改文案也不失效)', () => {
    const e = apiError(SEAT_CONFLICT_CODE, { conflict: [3] }, '座位已被占用')
    expect(isSeatConflict(e)).toBe(true)
    // 消息换成别的说法, 判定不变
    const e2 = apiError(SEAT_CONFLICT_CODE, { conflict: [3] }, '座位已被占用了哦')
    expect(isSeatConflict(e2)).toBe(true)
  })
})

describe('seatConflictOf', () => {
  it('冲突 → 返回座位列表', () => {
    expect(seatConflictOf(apiError(SEAT_CONFLICT_CODE, { conflict: [4, 5] }))).toEqual([4, 5])
  })

  it('冲突但列表为空 → 空数组(不是 null, 调用方据此走全量 refresh 兜底)', () => {
    expect(seatConflictOf(apiError(SEAT_CONFLICT_CODE, { conflict: [] }))).toEqual([])
  })

  it('不是冲突 → null', () => {
    expect(seatConflictOf(apiError(BAD_REQUEST_CODE, { conflict: [1] }))).toBeNull()
    expect(seatConflictOf(new Error('Network Error'))).toBeNull()
  })
})

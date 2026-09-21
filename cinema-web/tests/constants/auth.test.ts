import { describe, it, expect } from 'vitest'
import {
  AUTH_TOKEN_KEY,
  AUTH_USER_KEY,
  AUTH_CODES,
  type AuthCode,
} from '../../src/constants/auth'

/**
 * Auth 常量单测 — 锁定 wire-format 后端 ResultCode 一致 + localStorage key 单一来源.
 * 这些字符串/数字一旦漂移, 40101/40102 拦截失效 / 用户态不清理 → 严重 bug.
 */
describe('Auth constants — localStorage keys', () => {
  it('AUTH_TOKEN_KEY 锁定为 "cinema_token"', () => {
    expect(AUTH_TOKEN_KEY).toBe('cinema_token')
  })

  it('AUTH_USER_KEY 锁定为 "cinema_user"', () => {
    expect(AUTH_USER_KEY).toBe('cinema_user')
  })

  it('两个 key 不相等', () => {
    expect(AUTH_TOKEN_KEY).not.toBe(AUTH_USER_KEY)
  })
})

describe('Auth constants — wire codes (与 backend ResultCode 一致)', () => {
  it('UNAUTHORIZED = 40101', () => {
    expect(AUTH_CODES.UNAUTHORIZED).toBe(40101)
  })

  it('TOKEN_EXPIRED = 40102', () => {
    expect(AUTH_CODES.TOKEN_EXPIRED).toBe(40102)
  })

  it('两 code 不相等, 避免拦截器误判', () => {
    expect(AUTH_CODES.UNAUTHORIZED).not.toBe(AUTH_CODES.TOKEN_EXPIRED)
  })
})

describe('Auth constants — cross-validation (与 backend 一致)', () => {
  it('AuthCode 类型 = 40101 | 40102', () => {
    const codes: AuthCode[] = [AUTH_CODES.UNAUTHORIZED, AUTH_CODES.TOKEN_EXPIRED]
    expect(codes).toHaveLength(2)
    expect(codes).toContain(40101)
    expect(codes).toContain(40102)
  })
})
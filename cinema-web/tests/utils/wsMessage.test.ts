import { describe, it, expect } from 'vitest'
import { parseWsMessage } from '../../src/utils/wsMessage'
/**
 * parseWsMessage 单测 — ws.ts 的 JSON parse + 形态校验抽出后,
 * 无需 mock WebSocket 就能锁定协议容错.
 */
describe('parseWsMessage — 协议解析与校验', () => {
  it('正常 LOCKED 事件: 透传 sessionId + seats + type', () => {
    const raw = JSON.stringify({ type: 'LOCKED', sessionId: 123, seats: [10, 11] })
    const evt = parseWsMessage(raw)
    expect(evt).toEqual({ type: 'LOCKED', sessionId: 123, seats: [10, 11] })
  })

  it('RELEASED 与 SOLD 事件同样接受', () => {
    expect(parseWsMessage(JSON.stringify({ type: 'RELEASED', sessionId: 1, seats: [] }))).toEqual({
      type: 'RELEASED',
      sessionId: 1,
      seats: [],
    })
    expect(parseWsMessage(JSON.stringify({ type: 'SOLD', sessionId: 1, seats: [5] }))).toEqual({
      type: 'SOLD',
      sessionId: 1,
      seats: [5],
    })
  })

  it('sessionId 接受 string 类型(雪花 ID)', () => {
    const raw = JSON.stringify({ type: 'LOCKED', sessionId: '2095718202677161985', seats: [] })
    expect(parseWsMessage(raw)?.sessionId).toBe('2095718202677161985')
  })

  it('PONG 心跳: 返 null(调用方忽略)', () => {
    expect(parseWsMessage('PONG')).toBeNull()
  })

  it('空字符串: 返 null', () => {
    expect(parseWsMessage('')).toBeNull()
  })

  it('非 JSON 字符串: 返 null(不抛)', () => {
    expect(parseWsMessage('not-json')).toBeNull()
    expect(parseWsMessage('{')).toBeNull()
  })

  it('缺少 type: 返 null', () => {
    expect(parseWsMessage(JSON.stringify({ sessionId: 1, seats: [] }))).toBeNull()
  })

  it('type 不是合法枚举: 返 null', () => {
    expect(parseWsMessage(JSON.stringify({ type: 'UNKNOWN', sessionId: 1, seats: [] }))).toBeNull()
  })

  it('seats 不是数组: 返 null', () => {
    expect(parseWsMessage(JSON.stringify({ type: 'LOCKED', sessionId: 1, seats: 'oops' }))).toBeNull()
  })

  it('缺少 sessionId: 返 null', () => {
    expect(parseWsMessage(JSON.stringify({ type: 'LOCKED', seats: [] }))).toBeNull()
  })
})

// ============================================================================
// 事件序号(seq)的宽松透传
// ============================================================================

describe('parseWsMessage — seq 宽松透传', () => {
  it('seq 合法(正整数): 原样透传', () => {
    const evt = parseWsMessage(JSON.stringify({ type: 'LOCKED', sessionId: 1, seats: [3], seq: 42 }))
    expect(evt?.seq).toBe(42)
    expect(evt).toEqual({ type: 'LOCKED', sessionId: 1, seats: [3], seq: 42 })
  })

  it('seq = 0(服务端自增失败兜底): 透传为 0, 由下游按「无序号」处理', () => {
    const evt = parseWsMessage(JSON.stringify({ type: 'RELEASED', sessionId: 1, seats: [], seq: 0 }))
    expect(evt?.seq).toBe(0)
  })

  it('seq 缺失: **不挂载**该字段(保护既有严格形状断言)', () => {
    const evt = parseWsMessage(JSON.stringify({ type: 'LOCKED', sessionId: 1, seats: [3] }))
    expect(evt).toEqual({ type: 'LOCKED', sessionId: 1, seats: [3] })
    expect('seq' in (evt as object)).toBe(false)
  })

  it('seq 为字符串: 按缺失处理, 不挂载', () => {
    const evt = parseWsMessage(JSON.stringify({ type: 'LOCKED', sessionId: 1, seats: [], seq: '42' }))
    expect('seq' in (evt as object)).toBe(false)
  })

  it('seq 为 null: 按缺失处理, 不挂载', () => {
    const evt = parseWsMessage(JSON.stringify({ type: 'LOCKED', sessionId: 1, seats: [], seq: null }))
    expect('seq' in (evt as object)).toBe(false)
  })

  it('seq 为 NaN / Infinity(不可写成合法 JSON, 用 raw 拼): 按缺失处理, 不挂载', () => {
    const evt = parseWsMessage('{"type":"LOCKED","sessionId":1,"seats":[],"seq":1e999}')
    expect(evt).not.toBeNull()
    expect('seq' in (evt as object)).toBe(false)
  })

  it('seq 为负数: 透传(下游 filterBySeq 把 <=0 视为无序号)', () => {
    const evt = parseWsMessage(JSON.stringify({ type: 'LOCKED', sessionId: 1, seats: [], seq: -1 }))
    expect(evt?.seq).toBe(-1)
  })

  it('seq 合法不影响其余字段的校验(type 非法仍拒收)', () => {
    expect(parseWsMessage(JSON.stringify({ type: 'BOGUS', sessionId: 1, seats: [], seq: 1 }))).toBeNull()
  })
})
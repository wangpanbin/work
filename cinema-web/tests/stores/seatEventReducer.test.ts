import { describe, it, expect } from 'vitest'
import { computeApplyEvent } from '../../src/stores/seatEventReducer'

/**
 * computeApplyEvent 单测 — seat.ts applyEvent 的纯函数 seam.
 * 抽离后: 位图变更 + selected/conflictFlash 同步逻辑无需 mock Vue/Pinia 即可测.
 *
 * 注意: computeApplyEvent 不触发 1.8s 超时清理(那是 side effect, 留在 store wrapper 里).
 */
describe('computeApplyEvent — 位图同步', () => {
  it('LOCKED: 把 seats 位置的 lock bit 置 1', () => {
    const r = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set(), new Set(), 'LOCKED', [3, 5])
    expect(r.newLockBits[3]).toBe(1)
    expect(r.newLockBits[5]).toBe(1)
    expect(r.newSoldBits[3]).toBe(0)
  })

  it('RELEASED: 把 seats 位置的 lock bit 置 0', () => {
    const prev = new Uint8Array(10)
    prev[3] = 1
    const r = computeApplyEvent(prev, new Uint8Array(10), new Set(), new Set(), 'RELEASED', [3])
    expect(r.newLockBits[3]).toBe(0)
  })

  it('SOLD: lock + sold bit 同时置 1', () => {
    const r = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set(), new Set(), 'SOLD', [7])
    expect(r.newLockBits[7]).toBe(1)
    expect(r.newSoldBits[7]).toBe(1)
  })

  it('seats 为空: 不修改任何位图, 返回副本', () => {
    const prev = new Uint8Array(5)
    const r = computeApplyEvent(prev, new Uint8Array(5), new Set(), new Set(), 'LOCKED', [])
    expect(r.newLockBits).toEqual(prev)
    expect(r.newLockBits).not.toBe(prev)  // 返回新副本(避免外部 mutate)
  })
})

describe('computeApplyEvent — 越界 seat 跳过', () => {
  it('seat index < 0: 跳过', () => {
    const r = computeApplyEvent(new Uint8Array(5), new Uint8Array(5), new Set(), new Set(), 'LOCKED', [-1, 1])
    expect(r.newLockBits[1]).toBe(1)
    expect(r.newLockBits).toHaveLength(5)
  })

  it('seat index >= seatCount: 跳过', () => {
    const r = computeApplyEvent(new Uint8Array(5), new Uint8Array(5), new Set(), new Set(), 'LOCKED', [3, 99])
    expect(r.newLockBits[3]).toBe(1)
    expect(r.newLockBits).toHaveLength(5)
  })
})

describe('computeApplyEvent — selected 同步', () => {
  it('LOCKED seat 在 selected 里: 从 selected 移除, 加入 conflictFlash', () => {
    const r = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set([3]), new Set(), 'LOCKED', [3])
    expect(r.newSelected.has(3)).toBe(false)
    expect(r.newConflictFlash.has(3)).toBe(true)
    expect(r.hasConflict).toBe(true)
  })

  it('LOCKED seat 不在 selected 里: selected 不变, 不加入 conflictFlash', () => {
    const r = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set([5]), new Set(), 'LOCKED', [3])
    expect(r.newSelected.has(5)).toBe(true)
    expect(r.newConflictFlash.has(3)).toBe(false)
    expect(r.hasConflict).toBe(false)
  })

  it('RELEASED: 不动 selected, 不动 conflictFlash', () => {
    const r = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set([3]), new Set([3]), 'RELEASED', [3])
    expect(r.newSelected.has(3)).toBe(true)
    expect(r.newConflictFlash.has(3)).toBe(true)
  })

  it('SOLD seat 在 selected 里: 从 selected 移除, 加入 conflictFlash', () => {
    const r = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set([3]), new Set(), 'SOLD', [3])
    expect(r.newSelected.has(3)).toBe(false)
    expect(r.newConflictFlash.has(3)).toBe(true)
  })
})

describe('computeApplyEvent — 返回新副本(避免 mutate 输入)', () => {
  it('prevLockBits 不被 mutate', () => {
    const prev = new Uint8Array(5)
    computeApplyEvent(prev, new Uint8Array(5), new Set(), new Set(), 'LOCKED', [2])
    expect(prev[2]).toBe(0)
  })

  it('prevSelected 不被 mutate', () => {
    const prev = new Set([3])
    const r = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), prev, new Set(), 'LOCKED', [3])
    expect(prev.has(3)).toBe(true)
    expect(r.newSelected).not.toBe(prev)
  })
})

describe('computeApplyEvent — 未知 type 透传(不改位图)', () => {
  it('"UNKNOWN" type: 位图不变, 但 selected 也不动', () => {
    const r = computeApplyEvent(new Uint8Array(5), new Uint8Array(5), new Set([1]), new Set(), 'UNKNOWN', [1, 2])
    expect(r.newLockBits.every((b) => b === 0)).toBe(true)
    expect(r.newSelected.has(1)).toBe(true)
  })
})
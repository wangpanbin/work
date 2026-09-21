import { describe, it, expect } from 'vitest'
import {
  computeApplyEvent,
  computeApplyPreselect,
  parsePreselectFromQuery,
} from '../../src/stores/seatEventReducer'

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

// ============ T7: preselect 处理(对话式订票助手行动卡片)============

describe('parsePreselectFromQuery — URL 参数解析', () => {
  it('"52,53,54" → [52, 53, 54]', () => {
    expect(parsePreselectFromQuery('52,53,54')).toEqual([52, 53, 54])
  })

  it('空字符串 → []', () => {
    expect(parsePreselectFromQuery('')).toEqual([])
  })

  it('undefined / null / 数字 / 数组 → []', () => {
    expect(parsePreselectFromQuery(undefined)).toEqual([])
    expect(parsePreselectFromQuery(null)).toEqual([])
    expect(parsePreselectFromQuery(123)).toEqual([])
    expect(parsePreselectFromQuery([])).toEqual([])
  })

  it('"52, abc, -1, 54" → [52, 54] (非数字 / 负数跳过)', () => {
    expect(parsePreselectFromQuery('52, abc, -1, 54')).toEqual([52, 54])
  })

  it('含空格的 " 52 , 53 " → [52, 53]', () => {
    expect(parsePreselectFromQuery(' 52 , 53 ')).toEqual([52, 53])
  })

  it('末尾逗号 / 连续逗号 / 前导逗号 都不报错', () => {
    expect(parsePreselectFromQuery('52,53,')).toEqual([52, 53])
    expect(parsePreselectFromQuery(',52,53')).toEqual([52, 53])
    expect(parsePreselectFromQuery('52,,53')).toEqual([52, 53])
  })
})

describe('computeApplyPreselect — 行动卡片预选(spec §7.2)', () => {
  // 测试环境: 100 座全可选(空位图), myLocked 空
  const emptyLock = () => new Uint8Array(100)
  const emptySold = () => new Uint8Array(100)

  it('空选择 + preselect 2 个 → 选中 2(追加语义, 不覆盖)', () => {
    const r = computeApplyPreselect(
      new Set(), new Set(), [10, 20],
      emptyLock(), emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([10, 20]))
    expect(r.hasNewFlash).toBe(false)
  })

  it('已选 3 + preselect 2 → 选中 4(preselect 全加进 → 未超 maxSelect)', () => {
    // 既有的 [1,2,3] + preselect [10,20] 合并 = [1,2,3,10,20] 共 5 个,超 maxSelect=4
    // spec §7.2:按 preselect 中索引最小优先剔除 → 剔除 10
    const r = computeApplyPreselect(
      new Set([1, 2, 3]), new Set(), [10, 20],
      emptyLock(), emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([1, 2, 3, 20]))
    expect(r.newSelected.has(10)).toBe(false) // 10 被剔除(索引最小)
    expect(r.newSelected.has(20)).toBe(true)  // 20 保留
    expect(r.hasNewFlash).toBe(false)
  })

  it('已选 4 + preselect 1 → preselect 被剔除(无空间)', () => {
    const r = computeApplyPreselect(
      new Set([1, 2, 3, 4]), new Set(), [10],
      emptyLock(), emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([1, 2, 3, 4]))
    expect(r.hasNewFlash).toBe(false)
  })

  it('preselect 中部分座位已售(SOLD)→ 入 conflictFlash, 不入 selected', () => {
    const sold = emptySold()
    sold[10] = 1 // seat 10 已售
    const r = computeApplyPreselect(
      new Set(), new Set(), [10, 20],
      emptyLock(), sold, new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([20]))
    expect(r.newConflictFlash).toEqual(new Set([10]))
    expect(r.hasNewFlash).toBe(true)
  })

  it('preselect 中部分座位被他人锁(LOCKED_OTHER)→ 入 conflictFlash', () => {
    const lock = emptyLock()
    lock[10] = 1
    const r = computeApplyPreselect(
      new Set(), new Set(), [10, 20],
      lock, emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([20]))
    expect(r.newConflictFlash).toEqual(new Set([10]))
  })

  it('preselect 中部分是我已锁的(LOCKED_MINE)→ 跳过(不冲突)', () => {
    const lock = emptyLock()
    lock[10] = 1
    const r = computeApplyPreselect(
      new Set(), new Set(), [10, 20],
      lock, emptySold(), new Set([10]), 100, 4, // 我已锁 seat 10
    )
    expect(r.newSelected).toEqual(new Set([20])) // 10 跳过(我已锁)
    expect(r.newConflictFlash.has(10)).toBe(false) // 不入 flash
  })

  it('preselect 中重复座位(已在 selected)→ 不重复加, 不算 overflow', () => {
    const r = computeApplyPreselect(
      new Set([10]), new Set(), [10, 20],
      emptyLock(), emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([10, 20]))
    expect(r.newSelected.size).toBe(2) // 不是 3(去重)
  })

  it('preselect 中超界索引(>=seatCount)→ 跳过, 不抛错', () => {
    const r = computeApplyPreselect(
      new Set(), new Set(), [10, 200, -5],
      emptyLock(), emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([10]))
  })

  it('空 preselect → 返回原 selected 不变', () => {
    const r = computeApplyPreselect(
      new Set([1, 2]), new Set(), [],
      emptyLock(), emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([1, 2]))
    expect(r.hasNewFlash).toBe(false)
  })

  it('超 maxSelect: 已选 2 + preselect [3,4,5] → 选中 4, 剔除 preselect 中 idx=3 最小', () => {
    // 既有 [1,2] + preselect [3,4,5] 合并 = [1,2,3,4,5] 共 5 个, 超 maxSelect=4
    // 按 preselect 索引最小优先剔除 → 剔除 3
    const r = computeApplyPreselect(
      new Set([1, 2]), new Set(), [3, 4, 5],
      emptyLock(), emptySold(), new Set(), 100, 4,
    )
    expect(r.newSelected).toEqual(new Set([1, 2, 4, 5]))
  })
})
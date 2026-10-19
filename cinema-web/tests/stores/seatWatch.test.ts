import { describe, it, expect } from 'vitest'
import {
  computeRevalidateSelection,
  computeToggleWatch,
  computeReleasedWatchHits,
  removeWatched,
  formatSeatPositions,
} from '../../src/stores/seatWatch'

/**
 * seatWatch 纯函数接缝单测.
 *
 * 只断言外部可观察行为(返回的集合与分支), 不触碰 store / 组件 —— 本仓 vitest 跑在
 * environment: 'node', 挂不了 SFC, 逻辑必须下沉到纯函数才可测。
 */

// ============================================================================
// 权威重拉: 保留选座时的复核
// ============================================================================

describe('computeRevalidateSelection — 重拉后复核已选座位', () => {
  const bits = (n: number) => new Uint8Array(n)

  it('仍可用的座位: 保留在选座中', () => {
    const lock = bits(10)
    const r = computeRevalidateSelection(new Set([3, 4]), new Set(), lock, bits(10), new Set())
    expect(r.selected).toEqual(new Set([3, 4]))
    expect(r.lost).toEqual([])
    expect(r.hasConflict).toBe(false)
  })

  it('已变为已售的座位: 移出选座并加入冲突闪烁', () => {
    const sold = bits(10)
    sold[3] = 1
    const r = computeRevalidateSelection(new Set([3, 4]), new Set(), bits(10), sold, new Set())
    expect(r.selected).toEqual(new Set([4]))
    expect(r.conflictFlash.has(3)).toBe(true)
    expect(r.lost).toEqual([3])
    expect(r.hasConflict).toBe(true)
  })

  it('已变为他人锁定的座位: 移出选座并加入冲突闪烁', () => {
    const lock = bits(10)
    lock[5] = 1
    const r = computeRevalidateSelection(new Set([5]), new Set(), lock, bits(10), new Set())
    expect(r.selected).toEqual(new Set())
    expect(r.conflictFlash.has(5)).toBe(true)
  })

  it('变成「我已锁」的座位: 保留(不是冲突)', () => {
    const lock = bits(10)
    lock[5] = 1
    const r = computeRevalidateSelection(new Set([5]), new Set(), lock, bits(10), new Set([5]))
    expect(r.selected).toEqual(new Set([5]))
    expect(r.hasConflict).toBe(false)
  })

  it('既有的冲突闪烁集合被保留, 不被清空', () => {
    const r = computeRevalidateSelection(new Set(), new Set([9]), bits(10), bits(10), new Set())
    expect(r.conflictFlash.has(9)).toBe(true)
  })

  it('空选座: 原样返回', () => {
    const r = computeRevalidateSelection(new Set(), new Set(), bits(10), bits(10), new Set())
    expect(r.selected).toEqual(new Set())
    expect(r.hasConflict).toBe(false)
  })

  it('越界座位索引: 跳过而不是崩溃', () => {
    const r = computeRevalidateSelection(new Set([99, 2]), new Set(), bits(10), bits(10), new Set())
    expect(r.selected).toEqual(new Set([2]))
    expect(r.lost).toEqual([])
  })

  it('不 mutate 传入的 selected', () => {
    const prev = new Set([3])
    computeRevalidateSelection(prev, new Set(), bits(10), bits(10), new Set())
    expect(prev).toEqual(new Set([3]))
  })
})

// ============================================================================
// 座位关注: 切换
// ============================================================================

describe('computeToggleWatch — 关注切换的四个分支', () => {
  it('被他人锁定的座位: 新增关注', () => {
    const r = computeToggleWatch(new Set(), 5, 8, 'LOCKED_OTHER')
    expect(r.reason).toBe('added')
    expect(r.watched).toEqual(new Set([5]))
  })

  it('已售座位: 新增关注', () => {
    const r = computeToggleWatch(new Set(), 5, 8, 'SOLD')
    expect(r.reason).toBe('added')
    expect(r.watched.has(5)).toBe(true)
  })

  it('已关注的座位: 再点一次取消', () => {
    const r = computeToggleWatch(new Set([5]), 5, 8, 'LOCKED_OTHER')
    expect(r.reason).toBe('removed')
    expect(r.watched).toEqual(new Set())
  })

  it('★ 到达上限后仍能取消关注(取消必须永远可用)', () => {
    const full = new Set([1, 2, 3, 4, 5, 6, 7, 8])
    const r = computeToggleWatch(full, 3, 8, 'LOCKED_OTHER')
    expect(r.reason).toBe('removed')
    expect(r.watched.size).toBe(7)
  })

  it('到达上限时新增: 返回 limit 且不改动集合', () => {
    const full = new Set([1, 2, 3, 4, 5, 6, 7, 8])
    const r = computeToggleWatch(full, 9, 8, 'LOCKED_OTHER')
    expect(r.reason).toBe('limit')
    expect(r.watched.size).toBe(8)
    expect(r.watched.has(9)).toBe(false)
  })

  it('上限未满: 可继续加入', () => {
    const r = computeToggleWatch(new Set([1, 2]), 9, 8, 'SOLD')
    expect(r.reason).toBe('added')
    expect(r.watched.size).toBe(3)
  })

  it('可选座位: notWatchable(由调用方回落到普通选座)', () => {
    const r = computeToggleWatch(new Set(), 5, 8, 'AVAILABLE')
    expect(r.reason).toBe('notWatchable')
    expect(r.watched.size).toBe(0)
  })

  it('自己已锁的座位: notWatchable', () => {
    const r = computeToggleWatch(new Set(), 5, 8, 'LOCKED_MINE')
    expect(r.reason).toBe('notWatchable')
  })

  it('已选中的座位: notWatchable(选座与关注是两个不同层次)', () => {
    const r = computeToggleWatch(new Set(), 5, 8, 'SELECTED')
    expect(r.reason).toBe('notWatchable')
  })

  it('非法索引: notWatchable 且不抛错', () => {
    expect(computeToggleWatch(new Set(), -1, 8, 'SOLD').reason).toBe('notWatchable')
    expect(computeToggleWatch(new Set(), 1.5, 8, 'SOLD').reason).toBe('notWatchable')
  })

  it('不 mutate 传入的 watched', () => {
    const prev = new Set([1])
    computeToggleWatch(prev, 2, 8, 'SOLD')
    expect(prev).toEqual(new Set([1]))
  })

  it('返回的是新 Set, 不是原引用', () => {
    const prev = new Set<number>()
    const r = computeToggleWatch(prev, 2, 8, 'SOLD')
    expect(r.watched).not.toBe(prev)
  })
})

// ============================================================================
// 座位关注: 释放命中
// ============================================================================

describe('computeReleasedWatchHits — 释放提醒的命中判定', () => {
  const bits = (n: number) => new Uint8Array(n)

  it('被关注且释放后确实可选: 命中', () => {
    const lock = bits(10)
    const sold = bits(10)
    const hits = computeReleasedWatchHits(new Set([5]), [5], lock, sold)
    expect(hits).toEqual([5])
  })

  it('★ 未被关注的座位释放: 不命中(不制造额外打扰)', () => {
    const hits = computeReleasedWatchHits(new Set(), [5], bits(10), bits(10))
    expect(hits).toEqual([])
  })

  it('★ 释放后仍被他人锁走: 不命中(不给"已经为你留好"的错觉)', () => {
    const lock = bits(10)
    lock[5] = 1
    const hits = computeReleasedWatchHits(new Set([5]), [5], lock, bits(10))
    expect(hits).toEqual([])
  })

  it('释放后已售: 不命中', () => {
    const sold = bits(10)
    sold[5] = 1
    expect(computeReleasedWatchHits(new Set([5]), [5], bits(10), sold)).toEqual([])
  })

  it('不在本次释放列表里的关注座位: 不命中', () => {
    expect(computeReleasedWatchHits(new Set([5]), [7], bits(10), bits(10))).toEqual([])
  })

  it('多个命中: 升序返回, 便于提示按排序列出', () => {
    const hits = computeReleasedWatchHits(new Set([9, 2, 5]), [9, 2, 5, 1], bits(10), bits(10))
    expect(hits).toEqual([2, 5, 9])
  })

  it('越界座位索引: 跳过', () => {
    expect(computeReleasedWatchHits(new Set([99]), [99], bits(10), bits(10))).toEqual([])
  })

  it('空关注 / 空释放列表: 空结果', () => {
    expect(computeReleasedWatchHits(new Set(), [], bits(10), bits(10))).toEqual([])
  })
})

describe('removeWatched — 一次性关注结束后清理', () => {
  it('移除指定座位', () => {
    expect(removeWatched(new Set([1, 2, 3]), [2])).toEqual(new Set([1, 3]))
  })

  it('未关注的座位: 无副作用', () => {
    const r = removeWatched(new Set([1]), [7])
    expect(r).toEqual(new Set([1]))
  })

  it('空列表: 原样返回同一引用', () => {
    const prev = new Set([1])
    expect(removeWatched(prev, [])).toBe(prev)
  })
})

describe('formatSeatPositions — 命中座位转可读位置', () => {
  const fmt = (row: number, col: number) => `${row}排${col}座`

  it('少量命中: 全部列出', () => {
    const r = formatSeatPositions([2, 9], 8, fmt, ',')
    expect(r.text).toBe('1排3座,2排2座')
    expect(r.truncated).toBe(0)
  })

  it('命中过多: 截断并给出剩余数量(合并成一条提示, 不刷屏)', () => {
    const r = formatSeatPositions([0, 1, 2, 3, 4, 5], 8, fmt, ',', 4)
    expect(r.truncated).toBe(2)
    expect(r.text.split(',').length).toBe(4)
  })

  it('单个命中', () => {
    expect(formatSeatPositions([5], 8, fmt, ',').text).toBe('1排6座')
  })

  it('空命中: 空文本', () => {
    expect(formatSeatPositions([], 8, fmt, ',').text).toBe('')
  })
})

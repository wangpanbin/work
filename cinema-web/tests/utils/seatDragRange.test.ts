import { describe, it, expect } from 'vitest'
import { computeDragRange, type SeatStatus } from '../../src/utils/seatDragRange'

/**
 * 拖拽连选区间计算 —— 纯函数接缝单测。
 *
 * 只断言外部可观察行为(返回的索引区间与截断标记), 不触碰 store / 组件 ——
 * 本仓 vitest 跑在 environment: 'node', 挂不了 SFC, 逻辑必须下沉到纯函数才可测。
 *
 * 座位索引按行主序排布: index = (row - 1) * cols + col。
 * 用 cols = 10 举例: 第 2 排是 10~19, 第 3 排是 20~29。
 */

/** 全部可用的状态查询 */
const allAvailable = (_idx: number): SeatStatus => 'AVAILABLE'

/** 指定若干座位不可用的状态查询 */
function withStatus(blocked: Record<number, SeatStatus>) {
  return (idx: number): SeatStatus => blocked[idx] ?? 'AVAILABLE'
}

describe('computeDragRange — 拖拽连选区间', () => {
  it('同行向右拖 3 座: 返回连续 3 个索引, 未截断', () => {
    const r = computeDragRange(10, 12, 10, allAvailable, 4)
    expect(r.indexes).toEqual([10, 11, 12])
    expect(r.truncated).toBe(false)
  })

  it('同行向左拖(反向): 索引按拖拽方向排列', () => {
    const r = computeDragRange(12, 10, 10, allAvailable, 4)
    expect(r.indexes).toEqual([12, 11, 10])
    expect(r.truncated).toBe(false)
  })

  it('跨行不连座: 返回空区间', () => {
    // cols=10 时 9 在第 1 排、10 在第 2 排
    const r = computeDragRange(9, 10, 10, allAvailable, 4)
    expect(r.indexes).toEqual([])
    expect(r.truncated).toBe(false)
  })

  it('撞到不可选座位: 区间截断, 且不跨越该座位', () => {
    const r = computeDragRange(10, 14, 10, withStatus({ 12: 'SOLD' }), 4)
    expect(r.indexes).toEqual([10, 11])
    expect(r.truncated).toBe(true)
  })

  it('撞到他人锁定的座位: 同样截断', () => {
    const r = computeDragRange(10, 14, 10, withStatus({ 13: 'LOCKED_OTHER' }), 4)
    expect(r.indexes).toEqual([10, 11, 12])
    expect(r.truncated).toBe(true)
  })

  it('撞到已选座位: 同样截断(连座必须连续可用)', () => {
    const r = computeDragRange(10, 14, 10, withStatus({ 11: 'SELECTED' }), 4)
    expect(r.indexes).toEqual([10])
    expect(r.truncated).toBe(true)
  })

  it('锚点本身不可选: 不成区间', () => {
    const r = computeDragRange(10, 12, 10, withStatus({ 10: 'LOCKED_OTHER' }), 4)
    expect(r.indexes).toEqual([])
    expect(r.truncated).toBe(false)
  })

  it('超出 maxSelect: 截断到上限', () => {
    const r = computeDragRange(10, 15, 10, allAvailable, 3)
    expect(r.indexes).toEqual([10, 11, 12])
    expect(r.truncated).toBe(true)
  })

  it('恰好等于 maxSelect 且正好落在悬停点: 不算截断', () => {
    const r = computeDragRange(10, 12, 10, allAvailable, 3)
    expect(r.indexes).toEqual([10, 11, 12])
    expect(r.truncated).toBe(false)
  })

  it('锚点与悬停点相同: 只返回自己', () => {
    const r = computeDragRange(10, 10, 10, allAvailable, 4)
    expect(r.indexes).toEqual([10])
    expect(r.truncated).toBe(false)
  })

  it('非法输入(负索引 / cols<=0 / maxSelect<=0): 返回空区间', () => {
    expect(computeDragRange(-1, 12, 10, allAvailable, 4).indexes).toEqual([])
    expect(computeDragRange(10, 12, 0, allAvailable, 4).indexes).toEqual([])
    expect(computeDragRange(10, 12, 10, allAvailable, 0).indexes).toEqual([])
  })

  it('反向拖拽时撞到不可选座位: 从锚点侧截断', () => {
    const r = computeDragRange(14, 10, 10, withStatus({ 12: 'SOLD' }), 4)
    expect(r.indexes).toEqual([14, 13])
    expect(r.truncated).toBe(true)
  })
})

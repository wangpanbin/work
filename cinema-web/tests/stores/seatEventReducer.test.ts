import { describe, it, expect } from 'vitest'
import {
  computeApplyEvent,
  computeApplyPreselect,
  parsePreselectFromQuery,
  filterBySeq,
} from '../../src/stores/seatEventReducer'

/**
 * computeApplyEvent 单测 — seat.ts applyEvent 的纯函数 seam。
 * 抽离后: 位图变更 + selected/conflictFlash 同步逻辑无需 mock Vue/Pinia 即可测。
 *
 * 注意: computeApplyEvent 不触发 1.8s 超时清理(那是 side effect, 留在 store wrapper 里)。
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

describe('computeApplyEvent — RELEASED 双位清零(退票残留已售修复)', () => {
  it('已售座位收到释放事件: 已售位归零(退票路径, 修复的核心断言)', () => {
    const lock = new Uint8Array(10)
    const sold = new Uint8Array(10)
    sold[5] = 1
    const r = computeApplyEvent(lock, sold, new Set(), new Set(), 'RELEASED', [5])
    expect(r.newSoldBits[5]).toBe(0)
    expect(r.newLockBits[5]).toBe(0)
  })

  it('仅被锁定的座位收到释放事件: 两个位都为 0(取消/超时路径, 幂等空操作)', () => {
    const lock = new Uint8Array(10)
    lock[5] = 1
    const r = computeApplyEvent(lock, new Uint8Array(10), new Set(), new Set(), 'RELEASED', [5])
    expect(r.newLockBits[5]).toBe(0)
    expect(r.newSoldBits[5]).toBe(0)
  })

  it('已售座位收到释放事件: 不影响其他座位的已售位', () => {
    const sold = new Uint8Array(10)
    sold[5] = 1
    sold[7] = 1
    const r = computeApplyEvent(new Uint8Array(10), sold, new Set(), new Set(), 'RELEASED', [5])
    expect(r.newSoldBits[5]).toBe(0)
    expect(r.newSoldBits[7]).toBe(1)
  })

  it('同座位 先释放后锁定: 终态 lock=1 sold=0(锁定正确胜出)', () => {
    let bits = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set(), new Set(), 'RELEASED', [3])
    bits = computeApplyEvent(bits.newLockBits, bits.newSoldBits, new Set(), new Set(), 'LOCKED', [3])
    expect(bits.newLockBits[3]).toBe(1)
    expect(bits.newSoldBits[3]).toBe(0)
  })

  it('同座位 先锁定后释放: 终态 lock=0 sold=0 —— 这是短暂的假可选窗口', () => {
    // ⚠️ 这不是 bug 断言, 是如实记录既有行为:
    // 退票(seq 低)与他人重锁(seq 高)若乱序到达, 客户端会按到达顺序应用,
    // 短暂把真实已被锁住的座位显示成可选。
    // 修复手段不是改这里的语义, 而是:
    //   ① 按座位的事件序号过滤乱序(见 filterBySeq)
    //   ② 释放事件后与断连重连后的防抖权威重拉收敛残留
    // 断言成"应该是 lock=1"是一条假断言 —— 请勿改写。
    let bits = computeApplyEvent(new Uint8Array(10), new Uint8Array(10), new Set(), new Set(), 'LOCKED', [3])
    bits = computeApplyEvent(bits.newLockBits, bits.newSoldBits, new Set(), new Set(), 'RELEASED', [3])
    expect(bits.newLockBits[3]).toBe(0)
    expect(bits.newSoldBits[3]).toBe(0)
  })

  it('越界座位索引的释放事件被跳过, 不会越界写位图', () => {
    const sold = new Uint8Array(5)
    const r = computeApplyEvent(new Uint8Array(5), sold, new Set(), new Set(), 'RELEASED', [99])
    expect(r.newSoldBits).toHaveLength(5)
  })
})

// ============================================================================
// 事件序号过滤: 必须按座位, 不能用整场次单值
// ============================================================================

describe('filterBySeq — 无序号 / 零序号走「总是应用」', () => {
  it('seq 为 undefined: 全部接受, 且不推进 lastSeq', () => {
    const last = [10, 10, 10]
    const r = filterBySeq(last, [0, 1, 2], undefined)
    expect(r.accepted).toEqual([0, 1, 2])
    expect(r.dropped).toEqual([])
    expect(r.nextLastSeq).toEqual([10, 10, 10])
  })

  it('seq = 0(服务端自增失败兜底): 全部接受 —— 关键, 否则 0 > lastSeq 恒假会丢光事件', () => {
    const last = [500, 500]
    const r = filterBySeq(last, [0, 1], 0)
    expect(r.accepted).toEqual([0, 1])
    expect(r.nextLastSeq).toEqual([500, 500])
  })

  it('seq 为负数: 同样走「总是应用」', () => {
    const r = filterBySeq([7], [0], -1)
    expect(r.accepted).toEqual([0])
    expect(r.nextLastSeq).toEqual([7])
  })

  it('seq 为 NaN / Infinity: 走「总是应用」', () => {
    expect(filterBySeq([7], [0], NaN).accepted).toEqual([0])
    expect(filterBySeq([7], [0], Infinity).accepted).toEqual([0])
  })
})

describe('filterBySeq — 按座位的单调推进', () => {
  it('新事件: 接受并把该座位的 lastSeq 推进到 seq', () => {
    const r = filterBySeq([1, 1, 1], [2], 9)
    expect(r.accepted).toEqual([2])
    expect(r.dropped).toEqual([])
    expect(r.nextLastSeq).toEqual([1, 1, 9])
  })

  it('同座位更旧的 seq: 丢弃(退票后重锁乱序的正确处理)', () => {
    // 座位 5 已应用 seq=11(重锁), 迟到的 seq=10(退票) 必须丢弃
    const r = filterBySeq([0, 0, 0, 0, 0, 11], [5], 10)
    expect(r.accepted).toEqual([])
    expect(r.dropped).toEqual([5])
    expect(r.nextLastSeq[5]).toBe(11)
  })

  it('同座位相同 seq(重复投递): 丢弃', () => {
    const r = filterBySeq([0, 0, 8], [2], 8)
    expect(r.accepted).toEqual([])
    expect(r.dropped).toEqual([2])
  })

  it('★ 跨座位的慢事件不被误杀(整场次单值方案的致命缺陷)', () => {
    // seq=5 锁座位 10, seq=6 释放座位 20。到达顺序反过来: 6 先到, 5 后到。
    // 用按座位数组: 座位10 的 lastSeq 仍是 0, 5 > 0 → 必须接受。
    const sized = () => new Array(30).fill(0)   // 与 store 里 seatCount 对齐的真实形状
    let last: number[] = sized()
    const first = filterBySeq(last, [20], 6)
    last = first.nextLastSeq
    const second = filterBySeq(last, [10], 5)
    expect(second.accepted).toEqual([10])
    expect(second.dropped).toEqual([])
    // 终态: 两个座位各自记住了自己的序号
    expect(second.nextLastSeq[10]).toBe(5)
    expect(second.nextLastSeq[20]).toBe(6)
  })

  it('★ lastSeq 尚未按 seatCount 初始化(空数组): 全部接受而不是静默判越界', () => {
    // 守卫缺失时的表现是 `s >= 0 === next.length` → 全部越界 → 事件被静默丢弃,
    // 表现为"连上 WebSocket 但座位图再也不更新"。
    const r = filterBySeq([], [20, 10], 5)
    expect(r.accepted).toEqual([20, 10])
    expect(r.dropped).toEqual([])
  })

  it('一个事件里混合新旧序号: 按座位分别判定', () => {
    const last = [0, 100, 0]
    const r = filterBySeq(last, [0, 1, 2], 50)
    expect(r.accepted).toEqual([0, 2])   // 0 与 2 从未应用过 → 接受
    expect(r.dropped).toEqual([1])      // 1 已应用到 100 > 50 → 丢弃
    expect(r.nextLastSeq).toEqual([50, 100, 50])
  })
})

describe('filterBySeq — 边界与不可变性', () => {
  it('越界座位索引被跳过(既不接受也不丢弃)', () => {
    const r = filterBySeq([0, 0, 0], [0, 99, -1], 5)
    expect(r.accepted).toEqual([0])
    expect(r.dropped).toEqual([])
  })

  it('非整数座位索引被跳过', () => {
    const r = filterBySeq([0, 0, 0], [0, 1.5], 5)
    expect(r.accepted).toEqual([0])
  })

  it('不 mutate 传入的 lastSeq', () => {
    const last = [1, 2, 3]
    filterBySeq(last, [0], 99)
    expect(last).toEqual([1, 2, 3])
  })

  it('空 seats: 返回空集合, lastSeq 原样', () => {
    const r = filterBySeq([1, 2], [], 5)
    expect(r.accepted).toEqual([])
    expect(r.nextLastSeq).toEqual([1, 2])
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
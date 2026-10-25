import { describe, it, expect, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { loadSeatSession, canLockSession, type SeatSessionDeps } from '../../src/composables/useSeatSession'
import { useSeatStore } from '../../src/stores/seat'
import type { SeatMap } from '../../src/api/seat'

/**
 * 选座页「装载一个场次」的编排契约(2026-10-25 交互流程审查 P0-1)。
 *
 * <p>**为什么要有这个模块**: 选座页原先只在 `onMounted` 里拉一次座位图。
 * Vue Router 4 对**同一路由记录只换 params** 会复用组件实例(`RouterView`
 * 渲染时不加 key), `onMounted` 不再触发 —— 于是「URL 变成 /seat/B, 页面仍是
 * A 场的座位图, WS 还连着 A」, 用户在 A 的图上选座、点确认, 发出的却是
 * B 的 sessionId → 锁错场次的钱付了。
 *
 * <p>本仓 vitest 跑在 `environment: 'node'`, 挂不了 SFC —— 所以「装载一个场次」
 * 的编排必须下沉成可单测的模块, 视图只负责把依赖注入进来。
 *
 * <p>**脆性**: 断言锁的是**调用顺序与不变量**(公开接口上的可观察结果),
 * 不是内部实现细节。
 */

interface Call {
  name: string
  args?: unknown[]
}

function makeDeps(overrides: Partial<SeatSessionDeps> = {}): {
  deps: SeatSessionDeps
  calls: Call[]
} {
  const calls: Call[] = []
  const deps: SeatSessionDeps = {
    closeWs: vi.fn(() => {
      calls.push({ name: 'closeWs' })
    }),
    resetSelection: vi.fn(() => {
      calls.push({ name: 'resetSelection' })
    }),
    loadMap: vi.fn(async (sid: string, generation: number) => {
      calls.push({ name: 'loadMap', args: [sid, generation] })
    }),
    applyPreselect: vi.fn((indexes: number[]) => {
      calls.push({ name: 'applyPreselect', args: [indexes] })
    }),
    connectWs: vi.fn((sid: string) => {
      calls.push({ name: 'connectWs', args: [sid] })
    }),
    isCurrent: vi.fn(() => true),
    ...overrides,
  }
  return { deps, calls }
}

const names = (calls: Call[]) => calls.map((c) => c.name)

describe('loadSeatSession — 装载一个场次的编排', () => {
  it('调用顺序固定为 关WS → 清选座 → 拉座位图 → 应用预选 → 连WS', async () => {
    const { deps, calls } = makeDeps()
    await loadSeatSession(deps, 'B', [52, 53], 1)
    expect(names(calls)).toEqual([
      'closeWs',
      'resetSelection',
      'loadMap',
      'applyPreselect',
      'connectWs',
    ])
  })

  it('拉的是被点名的那个场次, 连的也是同一个场次', async () => {
    const { deps, calls } = makeDeps()
    await loadSeatSession(deps, 'B', [], 1)
    expect(calls.find((c) => c.name === 'loadMap')?.args).toEqual(['B', 1])
    expect(calls.find((c) => c.name === 'connectWs')?.args).toEqual(['B'])
  })

  it('轮次号要一路传到拉座位图那一步 —— 否则实现方没法判断「回来的这份已过时」', async () => {
    const { deps, calls } = makeDeps()
    await loadSeatSession(deps, 'B', [], 7)
    // loadMap 必须拿到本轮的轮次号 —— A→B→A 快返时靠它作废旧响应
    expect(calls.find((c) => c.name === 'loadMap')?.args).toEqual(['B', 7])
  })

  it('预选按原样传给应用预选', async () => {
    const { deps, calls } = makeDeps()
    await loadSeatSession(deps, 'B', [52, 53], 1)
    expect(calls.find((c) => c.name === 'applyPreselect')?.args).toEqual([[52, 53]])
  })

  it('⚠️ 拉座位图失败时仍然必须连 WS(首拉失败不能中断实时同步)', async () => {
    const { deps, calls } = makeDeps({
      loadMap: vi.fn(async () => {
        calls.push({ name: 'loadMap' })
        throw new Error('seat-map 500')
      }),
    })
    await loadSeatSession(deps, 'B', [], 1)
    // 连 WS 必须在最后, 且不能因为拉图失败而缺席 ——
    // 否则用户看的是一张永不更新的过期座位图, 而且毫无察觉。
    expect(names(calls)).toEqual(['closeWs', 'resetSelection', 'loadMap', 'connectWs'])
  })

  it('拉座位图失败不产生未处理的 rejection(编排自己兜住)', async () => {
    const { deps } = makeDeps({
      loadMap: vi.fn(async () => {
        throw new Error('seat-map 500')
      }),
    })
    await expect(loadSeatSession(deps, 'B', [], 1)).resolves.toBeUndefined()
  })

  it('拉座位图失败时不应用预选(位图未知, 冲突判断失效)', async () => {
    const { deps, calls } = makeDeps({
      loadMap: vi.fn(async () => {
        calls.push({ name: 'loadMap' })
        throw new Error('seat-map 500')
      }),
    })
    await loadSeatSession(deps, 'B', [52, 53], 1)
    expect(names(calls)).not.toContain('applyPreselect')
  })

  it('预选为空时不调用应用预选', async () => {
    const { deps, calls } = makeDeps()
    await loadSeatSession(deps, 'B', [], 1)
    expect(names(calls)).toEqual(['closeWs', 'resetSelection', 'loadMap', 'connectWs'])
  })
})

describe('在途请求不得接管新场次(2026-10-25 评审发现)', () => {
  it('⚠️ 过时的那一轮 → 彻底中止: 不套用旧预选、不覆盖新场次的 WS', async () => {
    const { deps, calls } = makeDeps({
      // 当前轮次已经是 2 了, 手里这一轮(1)还在装 A
      isCurrent: vi.fn((g: number) => g === 2),
    })
    await loadSeatSession(deps, 'A', [52, 53], 1)
    // ① applyPreselect 拿的是 A 的预选, 套到 B 的图上就是选错座
    // ② connectWs(A) 会用旧场次覆盖新场次的 WS(wsHandle 是单变量)
    expect(names(calls)).toEqual(['closeWs', 'resetSelection', 'loadMap'])
  })

  it('⚠️ A → B → A 快返: 回到 A 的第一轮也不能套用旧预选(判据是轮次不是场次)', async () => {
    const { deps, calls } = makeDeps({
      // 当前轮次是 3(A → B → A 走了三轮), 第一轮 A 的轮次是 1
      isCurrent: vi.fn((g: number) => g === 3),
    })
    await loadSeatSession(deps, 'A', [52, 53], 1)
    // 只比场次 ID 的话, 这里场次又是 A 了 → 会被判成「当前场次」→ 旧预选套上来
    expect(names(calls)).toEqual(['closeWs', 'resetSelection', 'loadMap'])
  })

  it('当前那一轮 → 正常走完编排', async () => {
    const { deps, calls } = makeDeps({
      isCurrent: vi.fn((g: number) => g === 1),
    })
    await loadSeatSession(deps, 'B', [52, 53], 1)
    expect(names(calls)).toEqual([
      'closeWs',
      'resetSelection',
      'loadMap',
      'applyPreselect',
      'connectWs',
    ])
  })
})

describe('canLockSession — 已开场场次的锁座闸门', () => {
  it('剩余 0 秒 → 不能锁座(场次已开场)', () => {
    expect(canLockSession(0)).toBe(false)
  })

  it('剩余秒数 > 0 → 能锁座', () => {
    expect(canLockSession(1)).toBe(true)
    expect(canLockSession(3600)).toBe(true)
  })

  it('负数 / NaN(时钟异常)→ 不能锁座(宁可拦住, 不误放)', () => {
    expect(canLockSession(-1)).toBe(false)
    expect(canLockSession(Number.NaN)).toBe(false)
  })
})

/**
 * 座位图能被清空 —— 换场次时旧座位图必须从 store 里消失。
 *
 * <p>这是 P0-1 的关键一环: 旧座位图留在页面上, 用户就在**旧场次**的网格上
 * 选座, 而请求发往**新场次**。视图侧靠 `seatStore.map = null` 清它 ——
 * 这条锁住「store 的座位图确实能被清掉」, 免得哪天 Pinia 行为变了
 * (或字段被改名)而页面静默地留着旧图。
 */
describe('集成 — 换场次时旧座位图能从 store 里消失', () => {
  const fakeMap = (sessionId: string): SeatMap => ({
    sessionId,
    movieTitle: '测试影片',
    hallName: '1号厅',
    startTime: '2026-10-25 19:00:00',
    price: 45,
    rows: 2,
    cols: 3,
    vipRowNos: [],
    seatCount: 6,
    lockBitmap: 'AA==',
    soldBitmap: 'AA==',
    myLockedSeats: [],
  })

  it('装载后 map 有值, 清空后 map 为 null', () => {
    setActivePinia(createPinia())
    const store = useSeatStore()
    store.load(fakeMap('A'))
    expect(store.map?.sessionId).toBe('A')
    store.map = null
    expect(store.map).toBeNull()
  })
})

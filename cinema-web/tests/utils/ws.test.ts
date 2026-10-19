import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { statusOnConnect } from '../../src/utils/ws'

/**
 * 断线重连的权威重拉补偿 — 状态机契约。
 *
 * 背景: 2026-10-19 浏览器验收实测到的真 bug。
 * 停掉后端 → 客户端进入「正在连接实时同步…」 → 后端起来后重连成功,
 * 状态条回到「实时同步中」, 但网络面板里**一条 seat-map 请求都没有**,
 * 断连期间被人改掉的座位仍显示成旧状态。
 *
 * 根因: connect() 每次都先写 status='connecting', 于是 onopen 时
 * prev 恒为 'connecting', 而 SeatSelect 的判定是 `s === 'open' && prev === 'closed'`
 * —— 永远为假。首次建连与断线重连在状态机上变得**不可区分**。
 *
 * 修法: 重连期间保持 'closed'(见 statusOnConnect), 让 'closed' → 'open'
 * 这一跳专门代表「断线重连」。
 *
 * 注: 本仓 vitest 跑在 environment: 'node', 挂不了 SFC 也没装 jsdom,
 * createSeatWs 依赖 window/location/WebSocket, 无法直接单测;
 * 因此把判定下沉成纯函数 statusOnConnect 来测, 另用静态扫描锁住接线。
 */
describe('statusOnConnect — 连接开始时写入的状态', () => {
  it('首次建连: 写 connecting', () => {
    expect(statusOnConnect(false)).toBe('connecting')
  })

  it('断线重连: 保持 closed, 不写 connecting', () => {
    expect(statusOnConnect(true)).toBe('closed')
  })

  it('⚠️ 回归锁: 重连态必须与首连态不同(否则组件无法区分二者)', () => {
    // 这条断言就是本次 bug 的最小复现:
    // 两者一旦相同, SeatSelect 里 `prev === 'closed'` 就恒为假。
    expect(statusOnConnect(true)).not.toBe(statusOnConnect(false))
  })
})

describe('ws.ts 接线 — 静态契约', () => {
  const src = readFileSync(
    fileURLToPath(new URL('../../src/utils/ws.ts', import.meta.url)),
    'utf-8',
  )

  it('connect() 通过 statusOnConnect 赋值, 没有内联硬编码 connecting', () => {
    expect(src).toContain('status.value = statusOnConnect(everOpened)')
    expect(src).not.toMatch(/status\.value\s*=\s*'connecting'/)
  })

  it('onopen 里置 everOpened = true(否则永远当首次建连)', () => {
    const onOpen = src.slice(src.indexOf('ws.onopen = () => {'))
    expect(onOpen.slice(0, 200)).toContain('everOpened = true')
  })

  it('onclose 仍写 closed(重连期间 UI 需要显示「正在连接…」)', () => {
    expect(src).toContain("status.value = 'closed'")
  })
})

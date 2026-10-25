import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { statusOnConnect, wsBaseUrl } from '../../src/utils/ws'

/**
 * 去掉注释(块注释 / HTML 注释 / 行注释)。
 *
 * ⚠️ 做「不该出现某串」的断言前必须去注释 —— 本仓源码注释里大量写着
 * 「原先是怎么写的(为什么这是个坑)」, 拿原文去断言会**永远命中**。
 *
 * 行注释用 `[^:]` 前瞻避开 `http://` / `ws://` 这类协议里的双斜杠。
 */
function stripComments(src: string): string {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1')
}

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

/**
 * WebSocket 基址解析 —— 座位图与大屏共用一份(2026-10-24)。
 *
 * 背景: 原先两处各写一套策略 ——
 *   · 座位 WS: location.hostname + 端口 5173 时换成 8080(直连后端, 不走 Vite 代理)
 *   · 大屏 WS: 直接用 location.host(依赖 Vite 代理转发 /ws)
 * 两套策略不一致 → 同一个后端, 两条 socket 走不同路径。
 * 而 `vite preview` 只配了 server.proxy、没有 preview.proxy, 于是 preview 模式下
 * 大屏那条彻底连不上, 且没有任何错误面(用户只看到大屏一直空着)。
 *
 * 统一规则: 开发服务器端口(5173 / 4173)→ 直连后端 8080; 其余情况用当前 host。
 */
describe('wsBaseUrl — WS 基址解析', () => {
  const loc = (protocol: string, hostname: string, port: string) => ({ protocol, hostname, port })

  it('开发端口 5173 → 直连后端 8080(不走 Vite 代理)', () => {
    expect(wsBaseUrl(loc('http:', 'localhost', '5173'))).toBe('ws://localhost:8080')
  })

  it('preview 端口 4173 → 同样直连后端 8080', () => {
    expect(wsBaseUrl(loc('http:', 'localhost', '4173'))).toBe('ws://localhost:8080')
  })

  it('非开发端口 → 保留当前 host 与端口(部署形态靠同源代理)', () => {
    expect(wsBaseUrl(loc('http:', 'cinema.example.com', '3000'))).toBe('ws://cinema.example.com:3000')
  })

  it('无端口(默认 80/443)→ 不带端口', () => {
    expect(wsBaseUrl(loc('http:', 'cinema.example.com', ''))).toBe('ws://cinema.example.com')
  })

  it('https → wss(否则浏览器直接拒绝混合内容)', () => {
    expect(wsBaseUrl(loc('https:', 'cinema.example.com', ''))).toBe('wss://cinema.example.com')
    expect(wsBaseUrl(loc('https:', 'localhost', '5173'))).toBe('wss://localhost:8080')
  })

  it('协议不是 https: 时一律按 ws(不猜 wss)', () => {
    expect(wsBaseUrl(loc('http:', 'localhost', '5173'))).toBe('ws://localhost:8080')
  })
})

describe('ws.ts 接线 — 静态契约', () => {
  const src = readFileSync(
    fileURLToPath(new URL('../../src/utils/ws.ts', import.meta.url)),
    'utf-8',
  )
  /** 去掉注释 —— 注释里会写「原先怎么写的」, 拿它当断言依据会永远命中。 */
  const code = stripComments(src)

  it('connect() 通过 statusOnConnect 赋值, 没有内联硬编码 connecting', () => {
    expect(code).toContain('status.value = statusOnConnect(everOpened)')
    expect(code).not.toMatch(/status\.value\s*=\s*'connecting'/)
  })

  it('onopen 里置 everOpened = true(否则永远当首次建连)', () => {
    const onOpen = code.slice(code.indexOf('ws.onopen = () => {'))
    expect(onOpen.slice(0, 200)).toContain('everOpened = true')
  })

  it('onclose 仍写 closed(重连期间 UI 需要显示「正在连接…」)', () => {
    expect(code).toContain("status.value = 'closed'")
  })

  it('座位 WS 走 wsBaseUrl(不自己再写一套端口策略)', () => {
    expect(code).toContain('`${wsBaseUrl(location)}/ws/seat/')
    expect(code).not.toMatch(/location\.hostname.*5173/)
  })
})

/**
 * 两条 socket 必须共用同一份基址解析 —— 否则会重演
 * 「座位图连得上、大屏连不上」这种只在某个部署形态下复现的割裂。
 */
describe('WS 基址共享 — 静态契约', () => {
  const read = (rel: string) =>
    readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf-8')
  const code = (rel: string) => stripComments(read(rel))

  it('LiveDashboard.vue 走 wsBaseUrl, 不再自己拼 location.host', () => {
    const src = code('../../src/views/admin/LiveDashboard.vue')
    expect(src).toContain("from '../../utils/ws'")
    expect(src).toContain('wsBaseUrl(location)')
    expect(src).not.toMatch(/location\.host\b/)
  })

  it('vite.config.ts 配了 preview.proxy(preview 模式下 /api 与 /ws 才能打到后端)', () => {
    const src = read('../../vite.config.ts')
    expect(src).toContain('preview:')
    const previewBlock = src.slice(src.indexOf('preview:'))
    expect(previewBlock).toContain('/api')
    expect(previewBlock).toContain('/ws')
  })
})

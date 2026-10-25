import { describe, it, expect } from 'vitest'
import { code, fnBody, read } from './source'

/**
 * 公共 helper 自身的契约。
 *
 * <p>**为什么 helper 自己也要测**:全仓的静态契约断言都建立在 `code()` 与 `fnBody()`
 * 之上 —— 它们一旦失效(剥注释没剥干净、函数体切串了),下面成百上千条断言会**一起变成
 * 恒真**,而且没有任何报错。所以这两个函数的边界必须自己被锁住。
 *
 * <p>`code()` 读的是文件,所以断言用**真实业务源码**;`fnBody()` 是纯函数,用最小样例更稳。
 */

describe('code() — 剥注释', () => {
  it('注释文本不出现在结果里(剥干净了)', () => {
    const src = code('../../src/views/SeatSelect.vue')
    expect(src).not.toContain('P0-1: 同路由换场次必须重新装载')
    expect(src).not.toContain('P0-2: WS 事件导致 selected 减少时')
  })

  it('真实的代码内容仍在(没把源码一起剥掉)', () => {
    const src = code('../../src/views/SeatSelect.vue')
    expect(src).toContain('seatStore')
    expect(src).toContain('async function onConfirm')
  })

  it('代码里的 URL 不会被当成行注释切掉(行注释的正则不误伤协议)', () => {
    // favicon.ts 的 SVG data-uri 里有 http://www.w3.org/... —— 行注释正则不能把它吃掉
    const src = code('../../src/theme/favicon.ts')
    expect(src).toContain('http://www.w3.org/2000/svg')
  })
})

describe('fnBody() — 按顶层声明边界切函数体', () => {
  const SAMPLE = `const a = 1
function real() {
  const inner = 2
  return inner
}
function next() {
  return 3
}
`

  it('切到下一个顶层声明为止, 不串到隔壁函数', () => {
    const body = fnBody(SAMPLE, 'function real()')
    expect(body).toContain('const inner = 2')
    expect(body).not.toContain('function next()')
  })

  it('找不到声明时返回空串(而不是 slice(-1) 那样静默取末字符)', () => {
    expect(fnBody(SAMPLE, 'function notExist()')).toBe('')
  })

  it('函数体内部的缩进声明不会被误当成边界', () => {
    const body = fnBody(SAMPLE, 'function real()')
    expect(body).toContain('const inner = 2')
  })

  it('在真实源码上: goSeat 的函数体不含 goOrders', () => {
    const src = code('../../src/views/Payment.vue')
    const body = fnBody(src, 'function goSeat')
    expect(body).toContain('seatPath')
    expect(body).not.toContain('function goOrders')
  })

  it('边界认得 export 前缀 —— 否则一路取到文件末尾, 断言会查两遍同一段文本', () => {
    const src = code('../../src/api/admin.ts')
    const body = fnBody(src, 'export function dashboardSummary')
    expect(body).toContain('skipErrorToast')
    // 下一个导出函数不能被吃进来
    expect(body).not.toContain('export function fetchChatStats')
  })

  it('在真实源码上: adminShellContract 那条断言查的是两个不同函数', () => {
    const src = code('../../src/api/admin.ts')
    const summary = fnBody(src, 'export function dashboardSummary')
    const stats = fnBody(src, 'export function fetchChatStats')
    expect(summary).not.toBe(stats)
    expect(summary).toContain('skipErrorToast')
    expect(stats).toContain('skipErrorToast')
  })
})

describe('read() — 相对路径深度约定', () => {
  it('从 tests/ 下的任意同深目录都能读到 cinema-web/src', () => {
    expect(read('../../src/views/SeatSelect.vue')).toContain('<template>')
  })
})

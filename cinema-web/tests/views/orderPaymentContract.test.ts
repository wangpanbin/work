import { describe, it, expect } from 'vitest'
import { code, fnBody } from '../helpers/source'

/**
 * 订单列表页 + 支付页的**交互契约**(2026-10-25 交互流程审查 · 批次二)。
 *
 * 本仓 vitest 跑在 environment: 'node', 没装 jsdom / @vue/test-utils,
 * 挂不了 SFC —— 涉及视图层的判断只有两条路:
 *   ① 把逻辑下沉成纯函数测;
 *   ② 对 .vue 源码做静态契约断言(本文件, 与 frontendContract.test.ts 同路子)。
 *
 * 锁的是三件**界面骗人**的事:
 *   1. 订单列表把「请求失败」显示成「暂无订单记录」(用户本来有 20 单);
 *   2. 支付页超时后的按钮文案与实际行为不一致, 且点击毫无反应;
 *   3. 支付页没有页内「我的订单」入口。
 *
 * 脆性写在文件头: 断言针对源码文本, 重构时需同步更新。
 */

/**
 * 取出一个顶层声明的函数体 —— 边界是**下一个顶层声明**, 不是固定字符数。
 *
 * <p>⚠️ 固定窗口会串到隔壁函数: `fn(src, 'function goSeat')` 取 800 字符时,
 * 后面的 `goOrders()` 也被吃进来, 而它恰好也有一行 `clearRedirectTimer()` ——
 * 于是「删掉 goSeat 里那行」测试照样绿, 断言根本没锁住它声称的东西。
 */
const fn = (src: string, signature: string) => {
  const at = src.indexOf(signature)
  expect(at, `源码里找不到 ${signature}`).toBeGreaterThan(-1)
  return fnBody(src, signature)
}

/** 取出模板里所有 el-button 元素(含按钮内文案), 便于按条件定位某一个。 */
const elButtons = (src: string) =>
  [...src.matchAll(/<el-button\b[\s\S]*?<\/el-button>/g)].map((m) => m[0])

describe('订单列表 · 请求失败不得显示成「暂无订单记录」', () => {
  it('加载失败时页面给出错误态与重试按钮', () => {
    const src = code('../../src/views/OrderList.vue')

    // load() 必须自己兜住失败: 抛出只会让列表停在初始的空数组上
    const load = fn(src, 'async function load()')
    expect(load).toMatch(/catch\s*\{/)
    expect(load).toContain("t('order.loadFailed')")

    // 模板: 错误态 + 重试(样式走 main.css 共享的 .load-error, 不另抄一份)
    expect(src).toContain('v-if="loadError"')
    expect(src).toContain('class="load-error"')
    expect(src).toContain('role="alert"')
    expect(src).toContain('class="load-error-text"')
    expect(src).toMatch(/@click="reload"/)
    expect(src).toContain("t('common.retry')")
  })

  it('错误态与「暂无订单记录」互斥: 失败时不再渲染空状态', () => {
    const src = code('../../src/views/OrderList.vue')

    // 空状态必须是错误态的 else 分支 —— 两者不同时成立
    expect(src).toContain('v-else-if="!loading && orders.length === 0"')
    expect(src).not.toContain('v-if="!loading && orders.length === 0"')
    // 空状态文案本身不能被改掉(它说的是「真的没有订单」)
    expect(src).toContain(":description=\"t('order.empty')\"")
  })

  it('重试会重新拉列表, 成功之后错误态被清掉', () => {
    const src = code('../../src/views/OrderList.vue')

    expect(fn(src, 'function reload')).toContain('load()')
    // 成功路径必须把错误态清空, 否则重试成功后仍停在错误态上
    expect(fn(src, 'async function load()')).toContain('loadError.value = null')
  })
})

describe('支付页 · 超时后的按钮必须与行为一致', () => {
  it('超时后主按钮变成「重新选座」, 点了真的跳选座页', () => {
    const src = code('../../src/views/Payment.vue')

    // 文案与行为必须同一个意思
    expect(src).toContain("t('payment.expiredReselect')")
    expect(src).toMatch(/expired\s*\?\s*goSeat\(\)\s*:\s*onPay\(\)/)
    // 原先是 redirectToOrders(): 文案说「重新选座」, 行为却是跳订单列表
    expect(src).not.toMatch(/expired\s*\?\s*redirectToOrders\(\)/)
  })

  it('超时后的按钮不再是死按钮(autoRedirected 置位后的静默 return)', () => {
    const src = code('../../src/views/Payment.vue')
    // 任何按钮都不得再走 redirectToOrders —— 它在 3 秒窗口内是静默 return
    expect(src).not.toMatch(/@click="[^"]*redirectToOrders\(\)/)
    // 离开页面仍要清掉超时定时器, 否则用户会被从选座页拽回订单列表
    expect(fn(src, 'function goSeat')).toContain('clearRedirectTimer()')
  })

  it('超时后另有「查看我的订单」次要按钮', () => {
    const src = code('../../src/views/Payment.vue')

    const expiredBtn = elButtons(src).find((b) => b.includes('v-if="expired"'))
    expect(expiredBtn, '超时态没有单独的次要按钮').toBeTruthy()
    expect(expiredBtn).toContain('@click="goOrders()"')
    expect(expiredBtn).toContain("t('payment.backToOrders')")

    // goOrders 必须跳订单列表, 且离开前清掉超时定时器
    const goOrders = fn(src, 'function goOrders')
    expect(goOrders).toContain('ROUTE_PATHS.orderList')
    expect(goOrders).toContain('clearRedirectTimer()')
  })

  it('超时提示原地可见, 不再只靠一闪而过的 toast', () => {
    const src = code('../../src/views/Payment.vue')

    expect(src).toMatch(/v-if="expired"/)
    expect(src).toContain('status-expired')
    // 横幅与按钮组说的是同一件事: 超时后给「重新选座 / 查看订单」两个出口。
    // 横幅不能还写「即将跳转到订单列表」—— 那是自动跳转 toast 的文案,
    // 放在原地横幅上与同屏的两个按钮互相打架(文案与行为必须同一个意思)。
    const banner = src.slice(src.indexOf('status-expired'))
    expect(banner).toContain("t('payment.expiredNotice')")
    expect(banner).not.toContain("t('payment.expiredWarn')")
    // 自动跳转那条 toast 仍然用 expiredWarn(它说的是「即将跳转」)
    expect(src).toContain("t('payment.expiredWarn')")
  })
})

describe('支付页 · 页内要有「我的订单」入口', () => {
  it('页内有一个跳订单列表的入口, 不用靠顶栏 chip', () => {
    const src = code('../../src/views/Payment.vue')

    expect(src).toContain('class="orders-entry"')
    expect(src).toMatch(/@click="goOrders\(\)"/)
    expect(src).toContain("t('payment.backToOrders')")
  })

  it('页内入口放在卡片上方, 且不抢主操作的层级', () => {
    const src = code('../../src/views/Payment.vue')

    // 位置: 入口在票面卡片之前 —— 卡片里是「立即支付」这类主操作
    expect(src.indexOf('class="orders-entry"')).toBeGreaterThan(-1)
    expect(src.indexOf('class="orders-entry"')).toBeLessThan(src.indexOf('class="payment-card'))

    // 层级: 不是主色实心按钮, 文字走弱墨
    const entry = src.match(/<button[^>]*class="orders-entry"[^>]*>/)
    expect(entry, '页内入口元素不存在').toBeTruthy()
    expect(entry![0]).not.toContain('type="primary"')

    const css = src.slice(src.indexOf('.orders-entry {'), src.indexOf('.orders-entry {') + 400)
    expect(css).toMatch(/color:\s*var\(--ink-[23]\)/)
    expect(css).not.toMatch(/color:\s*var\(--accent\)/)
  })

  it('超时态只保留一个「查看我的订单」入口(页内入口在超时时隐藏)', () => {
    const src = code('../../src/views/Payment.vue')

    // 页内入口在超时态隐藏 —— 否则同屏两个「查看我的订单」
    const entry = src.match(/<button[^>]*class="orders-entry"[^>]*>/)
    expect(entry, '页内入口元素不存在').toBeTruthy()
    expect(entry![0]).toContain('v-if="!expired"')

    // 超时分支里的次要按钮仍在 —— 它是超时态唯一的订单出口
    const expiredBtn = elButtons(src).find((b) => b.includes('v-if="expired"'))
    expect(expiredBtn, '超时态没有单独的次要按钮').toBeTruthy()
    expect(expiredBtn).toContain("t('payment.backToOrders')")
  })
})

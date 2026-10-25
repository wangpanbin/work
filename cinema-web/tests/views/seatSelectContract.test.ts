import { describe, it, expect } from 'vitest'
import { code, fnBody, read } from '../helpers/source'
import { parse, compileScript } from 'vue/compiler-sfc'

/**
 * 选座页的接线契约(2026-10-25 交互流程审查, 批次一)。
 *
 * <p>本仓 vitest 跑在 `environment: 'node'`, 没装 jsdom / `@vue/test-utils`,
 * 挂不了 SFC —— 涉及视图层的判断只有两条路:
 * <ul>
 *   <li>① 把判断逻辑下沉成纯函数 / composable 测(见 `tests/composables/useSeatSession.test.ts`);</li>
 *   <li>② 对 `.vue` 源码做**静态契约断言**(本文件)。</li>
 * </ul>
 *
 * <p>本文件锁的是「改了但会静默失效」的那一类: 编排抽出来了却没接上线,
 * watch 没挂、确认框没在请求之前、按钮没禁用 —— 全都不报错, 界面看着正常,
 * 功能却是死的。
 *
 * <p>⚠️ **断言前必须剥注释**: 本仓的源码注释里大量写着「原先是怎么写的
 * (为什么这是个坑)」, 拿原文去断言会永远命中, 断言就变成恒真假绿。
 * 下面的 `code()` 与 `tests/views/frontendContract.test.ts` 顶部的实现一致。
 *
 * <p>脆性写在文件头: 断言针对源码文本, 重构时需同步更新。
 */

const SEAT_SELECT = '../../src/views/SeatSelect.vue'
const SEAT_ITEM = '../../src/components/SeatItem.vue'

describe('P0-1 — 同路由换场次必须重新装载座位图', () => {
  it('SeatSelect: 装载编排来自 useSeatSession 模块(视图不再自己拉座位图)', () => {
    const src = code(SEAT_SELECT)
    expect(src).toContain("from '../composables/useSeatSession'")
    expect(src).toContain('loadSeatSession(')
  })

  it('SeatSelect: 换场次的触发源是路由参数 —— 组件复用时 onMounted 不会重跑', () => {
    const src = code(SEAT_SELECT)
    // watch 的源必须随 route.params.sessionId 变化
    expect(src).toMatch(/seatRouteKey = computed\(\s*\(\)\s*=>[^\n]*route\.params\.sessionId/)
    expect(src).toContain('watch(seatRouteKey')
  })

  it('SeatSelect: 换场次走的是同一套装载编排(不是另写一份拉图逻辑)', () => {
    const src = code(SEAT_SELECT)
    const watcher = fnBody(src, 'watch(seatRouteKey')
    expect(watcher).toContain('runSeatSession(')
  })

  it('SeatSelect: 换场次要清掉上一场次的选座与座位图', () => {
    const src = code(SEAT_SELECT)
    const fn = fnBody(src, 'function resetSelection')
    expect(fn).toContain('seatStore.clearSelection()')
    // 旧座位图必须一起清: 它留在页面上时, 用户会在旧场次的网格上选座,
    // 而请求发往新场次 —— 正是 P0-1 的「锁错场次」。
    expect(fn).toContain('seatStore.map = null')
  })

  it('⚠️ 视图用的是**轮次**而不是场次 ID 判过时(A→B→A 快返时场次 ID 又相等了)', () => {
    const src = code(SEAT_SELECT)
    // ① 编排的守卫按轮次判
    expect(src).toContain('isCurrent: (generation) => generation === sessionGeneration')
    // ② 位图落库那一路也要有轮次守卫 —— 只判场次 ID 时,
    //    第一轮 A 的响应回来仍会 seatStore.load() 把第三轮刚套上的预选清空
    const refresh = fnBody(src, 'async function refreshSession')
    expect(refresh).toMatch(/generation !== sessionGeneration/)
    // ③ 轮次号要一路传到拉座位图那一步 —— 视图把 loadMapFor 注入编排, 编排再把轮次传下去
    expect(src).toContain('loadMap: loadMapFor')
    const session = code('../../src/composables/useSeatSession.ts')
    expect(session).toContain('deps.loadMap(sessionId, generation)')
    expect(src).toContain('loadMapFor(sid: string, generation: number)')
  })
})

describe('P0-2 — 同场次已有待支付单, 锁座前必须先告知', () => {
  it('SeatMap 带上存量待支付单号(前端才知道要提示)', () => {
    const src = code('../../src/api/seat.ts')
    expect(src).toContain('pendingOrderNo')
    expect(src).toContain('pendingExpireAt')
  })

  it('SeatSelect: 提示必须出现在锁座请求之前', () => {
    const src = code(SEAT_SELECT)
    const fn = fnBody(src, 'async function onConfirm')
    const confirmIdx = fn.indexOf('confirmPendingOrder(')
    const requestIdx = fn.indexOf('lockSeats(')
    expect(confirmIdx).toBeGreaterThan(-1)
    expect(confirmIdx).toBeLessThan(requestIdx)
    // 用户取消 → 直接 return, 不发请求
    expect(fn.slice(confirmIdx, requestIdx)).toContain('return')
  })

  it('⚠️ 读「同场次有没有待支付单」之前必须先权威重拉一次', () => {
    const src = code(SEAT_SELECT)
    const fn = fnBody(src, 'async function onConfirm')
    // 座位图是**装载时**的快照; 对话一键下单**不跳转**(停在聊天浮窗里) ——
    // 用户在同一个选座页上用聊天下了一笔单之后, 快照里仍是 null → 不弹确认框,
    // 而后端照样关掉那笔单。披露必须基于当下状态。
    const refreshIdx = fn.indexOf('refreshSession(')
    const readIdx = fn.indexOf('pendingOrderNo')
    expect(refreshIdx, 'onConfirm 没有在披露前重拉座位图').toBeGreaterThan(-1)
    expect(readIdx, 'onConfirm 没有读 pendingOrderNo').toBeGreaterThan(-1)
    expect(refreshIdx).toBeLessThan(readIdx)
  })

  it('SeatSelect: 确认框用约定的四个文案键, 且带上订单号', () => {
    const src = code(SEAT_SELECT)
    expect(src).toContain('ElMessageBox.confirm')
    expect(src).toContain("t('seat.pendingOrderWarn', { order:")
    expect(src).toContain("t('seat.pendingOrderTitle')")
    expect(src).toContain("t('seat.pendingOrderOk')")
    expect(src).toContain("t('seat.pendingOrderCancel')")
  })

  it('SeatSelect: 待支付单号取自座位图', () => {
    const src = code(SEAT_SELECT)
    expect(src).toContain('pendingOrderNo')
  })

  it('SeatSelect: 用户取消要有可观察反馈(不能静默返回)', () => {
    const src = code(SEAT_SELECT)
    const helper = fnBody(src, 'async function confirmPendingOrder')
    expect(helper).toContain('ElMessage.info')
  })

  it('SeatSelect: 取消后的 toast 用 pendingOrderCancelled, 不是弹窗按钮的 pendingOrderCancel', () => {
    const src = code(SEAT_SELECT)
    const helper = fnBody(src, 'async function confirmPendingOrder')
    // 取消后的 toast 要说「订单没下, 旧待支付单还在」——
    // 弹窗取消按钮那句「再想想 / Not now」当 toast 读起来别扭。
    expect(helper).toMatch(/ElMessage\.info\(t\('seat\.pendingOrderCancelled'\)\)/)
    // 弹窗的取消按钮文字保持不变
    expect(helper).toMatch(/cancelButtonText:\s*t\('seat\.pendingOrderCancel'\)/)
  })
})

describe('P1 — 点「我已锁」的座位不能毫无反应', () => {
  it('SeatItem: LOCKED_MINE 分支给出提示', () => {
    const src = code(SEAT_ITEM)
    expect(src).toContain('LOCKED_MINE')
    expect(src).toContain("t('seat.lockedMineHint')")
  })

  it('SeatItem: 提示只在选座失败之后发(成功的点击不弹提示)', () => {
    const src = code(SEAT_ITEM)
    const fn = fnBody(src, 'function onClick')
    expect(fn).toContain('if (ok) return')
    expect(fn.indexOf('seatStore.toggle(')).toBeLessThan(fn.indexOf('seat.lockedMineHint'))
  })
})

describe('P3 — 已开场场次前端要拦一道', () => {
  it('SeatSelect: 判定走 canLockSession(视图不自己判秒数)', () => {
    const src = code(SEAT_SELECT)
    expect(src).toContain('canLockSession(')
  })

  it('SeatSelect: 倒计时归零时禁用确认锁座按钮', () => {
    const src = code(SEAT_SELECT)
    expect(src).toContain(':disabled="sessionStarted"')
  })

  it('SeatSelect: 已开场时给出提示文案', () => {
    const src = code(SEAT_SELECT)
    expect(src).toContain("t('seat.sessionStarted')")
  })

  it('SeatSelect: onConfirm 里也拦一道(按钮禁用只是第一道)', () => {
    const src = code(SEAT_SELECT)
    const fn = fnBody(src, 'async function onConfirm')
    expect(fn).toContain('canLockSession(')
    expect(fn).toContain('seat.sessionStarted')
  })
})

describe('SFC 编译 — 我改过的两个 .vue 必须编译得过', () => {
  for (const rel of [SEAT_SELECT, SEAT_ITEM]) {
    it(`${rel} 的 script 块语法正确`, () => {
      const source = read(rel)
      const { descriptor, errors } = parse(source, { filename: rel })
      expect(errors.map((e) => (e as Error).message ?? String(e))).toEqual([])
      let thrown: unknown = null
      try {
        compileScript(descriptor, { id: rel })
      } catch (e) {
        thrown = e
      }
      expect(
        thrown,
        `${rel} 的 script 块有语法错误: ${thrown instanceof Error ? thrown.message : String(thrown)}`,
      ).toBeNull()
    })
  }
})

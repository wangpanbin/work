import { describe, it, expect } from 'vitest'
import { code } from '../helpers/source'

/**
 * 前端收口改动的**接线契约**(2026-10-24)。
 *
 * 本仓 vitest 跑在 environment: 'node', 没装 jsdom / @vue/test-utils,
 * 挂不了 SFC —— 涉及视图层的判断只有两条路:
 *   ① 把逻辑下沉成纯函数测(见 utils/ws.test.ts / utils/bitmap.test.ts);
 *   ② 对 .vue 源码做静态契约断言(本文件)。
 *
 * 本文件锁的是「改了但会静默失效」的那一类: 逻辑本身没写错,
 * 但没接上线 —— 事件处理器没绑、watch 没挂、定时器没清、参数传错位。
 * 这类问题的共同特征是**没有任何报错**, 界面看着正常, 功能却是死的。
 *
 * 脆性写在文件头: 断言针对源码文本, 重构时需同步更新。
 */

describe('C1 — 首拉失败不再让整页哑掉', () => {
  it('SeatSelect: 首拉被 try 包住, 失败不中断 onMounted(WS 必须连上)', () => {
    const mounted = code('../../src/views/SeatSelect.vue').slice(
      code('../../src/views/SeatSelect.vue').indexOf('onMounted(async () => {'),
    )
    const wsCall = mounted.indexOf('createSeatWs(')
    expect(wsCall).toBeGreaterThan(-1)
    // WS 建连必须在首拉的 try/catch 之后 —— 顺序反了又会被首拉失败带偏
    const tryIdx = mounted.indexOf('try {')
    expect(tryIdx).toBeLessThan(wsCall)
    // 首拉与 WS 之间必须有一层 catch
    expect(mounted.slice(tryIdx, wsCall)).toMatch(/catch\s*\{/)
  })

  it('SeatSelect: 有错误态与重试按钮', () => {
    const src = code('../../src/views/SeatSelect.vue')
    expect(src).toContain('loadError')
    expect(src).toContain('role="alert"')
    expect(src).toContain('@click="onManualRefresh"')
  })

  it('Payment: 有错误态与重试', () => {
    const src = code('../../src/views/Payment.vue')
    expect(src).toContain('loadError')
    expect(src).toContain('@click="reload"')
  })

  it('MovieDetail: 影片与场次两个维度都有错误态', () => {
    const src = code('../../src/views/MovieDetail.vue')
    expect(src).toContain('loadError')
    expect(src).toContain('sessionsError')
  })

  it('AdminHome / LiveDashboard: 有错误态', () => {
    expect(code('../../src/views/admin/AdminHome.vue')).toContain('loadError')
    expect(code('../../src/views/admin/LiveDashboard.vue')).toContain('loadError')
  })
})

describe('C3(a) — 手动刷新不能把 MouseEvent 当 boolean', () => {
  it('SeatSelect: 按钮绑的是无参包装函数, 不是 refresh 本身', () => {
    const src = code('../../src/views/SeatSelect.vue')
    expect(src).toContain('async function onManualRefresh()')
    // ⚠️ 回归锁: 直接绑 refresh 会把 MouseEvent 当成 preserveSelection=true
    expect(src).not.toMatch(/@click="refresh"/)
    expect(src).not.toMatch(/@click="refresh\("/)
  })
})

describe('C3(b) — 支付页 3 秒自动跳转会把你从选座页拽回来', () => {
  it('Payment: 定时器句柄被存下并在 onUnmounted 取消', () => {
    const src = code('../../src/views/Payment.vue')
    expect(src).toMatch(/let\s+redirectTimer/)
    expect(src).toMatch(/redirectTimer\s*=\s*setTimeout/)
    expect(src).toContain('clearTimeout(redirectTimer)')
    expect(src).toContain('onUnmounted(')
  })

  it('Payment: 离开页面前主动取消(goSeat 路径)', () => {
    const src = code('../../src/views/Payment.vue')
    const goSeat = src.slice(src.indexOf('function goSeat'))
    expect(goSeat).toContain('clearRedirectTimer()')
  })
})

describe('C3(c) — 订单列表第 21 条之后不可达', () => {
  it('OrderList: 请求带真实分页参数', () => {
    const src = code('../../src/views/OrderList.vue')
    expect(src).toContain('page: pageNum.value')
    expect(src).toContain('size: pageSize.value')
    // 原先是写死的 page: 1, size: 20
    expect(src).not.toMatch(/page:\s*1,\s*size:\s*20/)
  })

  it('OrderList: 模板里有 el-pagination', () => {
    expect(code('../../src/views/OrderList.vue')).toContain('<el-pagination')
  })

  it('OrderList: 切状态筛选回到第 1 页', () => {
    const src = code('../../src/views/OrderList.vue')
    const fn = src.slice(src.indexOf('function onStatusChange'))
    expect(fn.slice(0, 200)).toContain('pageNum.value = 1')
  })
})

describe('C3(d) — 取消/退票后 tab 角标不刷新', () => {
  it('OrderList: onCancel 与 onRefund 都重拉角标', () => {
    const src = code('../../src/views/OrderList.vue')
    const cancel = src.slice(src.indexOf('async function onCancel'))
    const refund = src.slice(src.indexOf('async function onRefund'))
    expect(cancel.slice(0, 1200)).toContain('loadCounts()')
    expect(refund.slice(0, 1200)).toContain('loadCounts()')
  })
})

describe('C3(e) — 换账号后浮窗里还留着上一个账号的对话', () => {
  it('ChatWidget: watch 用户 id, 清消息 + 换 chatSessionId', () => {
    const src = code('../../src/components/chat/ChatWidget.vue')
    expect(src).toContain('userStore.user?.id')
    expect(src).toContain('messages.value = []')
    expect(src).toContain('resetChatSession()')
  })
})

describe('C3(f) — 切日期发两次请求', () => {
  it('MovieDetail: radio-group 不再绑 @change="loadSessions"', () => {
    const src = code('../../src/views/MovieDetail.vue')
    expect(src).not.toContain('@change="loadSessions"')
    // watch 仍保留 —— 只由它驱动
    expect(src).toContain('watch(selectedDate')
  })
})

describe('C3(g) — 管理端图表/榜单缺数据时无提示', () => {
  it('AdminHome / LiveDashboard: 有 loadError + 重试', () => {
    for (const f of ['../../src/views/admin/AdminHome.vue', '../../src/views/admin/LiveDashboard.vue']) {
      expect(code(f)).toContain('loadError')
    }
  })
})

describe('C3(i) — logout() 定义了却没有入口', () => {
  it('AdminHome: 模板里绑定了 logout', () => {
    expect(code('../../src/views/admin/AdminHome.vue')).toContain('@click="logout"')
  })
})

describe('C3(k) — 缺失票房算出 NaN%', () => {
  it('Dashboard: 票房缺失按 0 算', () => {
    const src = code('../../src/views/admin/Dashboard.vue')
    expect(src).toContain('Number(m.revenue ?? 0)')
    expect(src).not.toMatch(/Number\(m\.revenue\)\s*\//)
  })
})

describe('C3(m) — 无障碍: 状态要能被读屏念出来', () => {
  it('LiveDashboard: 连接状态带 role=status 与 aria-live', () => {
    const src = code('../../src/views/admin/LiveDashboard.vue')
    expect(src).toContain('role="status"')
    expect(src).toContain('aria-live="polite"')
  })

  it('ActionCard: 内层按钮自己绑 @click.stop', () => {
    expect(code('../../src/components/chat/ActionCard.vue')).toContain('@click.stop="onClick"')
  })
})

describe('A8 — 管理端场次分页', () => {
  it('api/admin.ts 导出 sessionPage, 走新端点 /admin/sessions/page', () => {
    const src = code('../../src/api/admin.ts')
    expect(src).toContain('sessionPage')
    // axios baseURL 是 /api, 所以这里写的是不含 /api 前缀的相对路径
    expect(src).toContain("'/admin/sessions/page'")
  })

  it('SessionManage.vue 用 sessionPage 而不是旧的 sessionList', () => {
    const src = code('../../src/views/admin/SessionManage.vue')
    expect(src).toContain('sessionPage')
    expect(src).not.toMatch(/\bsessionList\b/)
  })

  it('SessionManage.vue 有 el-pagination', () => {
    expect(code('../../src/views/admin/SessionManage.vue')).toContain('<el-pagination')
  })
})

describe('C2 — 管理端「恢复位图」入口(PRODUCT.md 的承诺)', () => {
  it('api/admin.ts 导出 recoverSessionBitmap', () => {
    expect(code('../../src/api/admin.ts')).toContain('recoverSessionBitmap')
  })

  it('SessionManage.vue 每行有恢复位图按钮', () => {
    const src = code('../../src/views/admin/SessionManage.vue')
    expect(src).toContain('recoverSessionBitmap')
    expect(src).toContain('admin.sessionsRecoverBitmap')
  })
})

describe('IT-12 — 动态 lang 与键盘可达', () => {
  it('i18n/index.ts 运行时同步 document.documentElement.lang(静态 lang 只是兜底)', () => {
    const src = code('../../src/i18n/index.ts')
    expect(src).toContain('applyHtmlLang')
    expect(src).toMatch(/documentElement\.lang/)
  })
})

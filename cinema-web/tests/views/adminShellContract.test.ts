import { describe, it, expect } from 'vitest'
import { code, fnBody as fnBodyOf } from '../helpers/source'
import { adminCrumbs } from '../../src/views/admin/breadcrumb'

/**
 * adminShellContract.test.ts — 管理端外壳(经营看板 / 管理首页 / 实时大屏 / 助手用量看板)的接线契约。
 *
 * <p>**为什么是静态契约**:本仓库 vitest 跑在 environment: 'node', 没装 jsdom / @vue/test-utils,
 * 挂不了 SFC —— 涉及视图层的判断只有两条路(见 AGENTS.md):
 * ① 逻辑下沉成纯函数测;② 对 .vue 源码做静态契约断言(本文件走②, 纯函数部分见 D5)。
 *
 * <p>**必须先剥注释再断言**:本仓源码注释里大量写着「原先是怎么写的(为什么这是个坑)」,
 * 拿原文去断言会永远命中, 断言就变成恒真假绿。`code()` 的实现照抄
 * `tests/views/frontendContract.test.ts` 顶部的同名 helper。
 */

/**
 * 取出某个导出函数的函数体 —— 边界是**下一个顶层声明**。
 *
 * <p>用来把「这个函数声明了什么」与「文件里别处声明了什么」分开断言。
 * ⚠️ 固定字符窗口会串到隔壁函数(那边恰好也有同样的调用)→ 断言变恒真。
 */
const fnBody = (src: string, name: string): string =>
  fnBodyOf(src, `export function ${name}`)

describe('D1 — 管理端经营看板: 请求失败不再整页空白', () => {
  it('Dashboard: load() 有 catch, 失败被吃掉而不是让 Promise 裸奔', () => {
    const src = code('../../src/views/admin/Dashboard.vue')
    const fn = fnBodyOf(src, 'async function load()')
    // 原稿只有 try/finally 没有 catch —— 失败时 data 保持 null, 整块内容被 v-if 挡掉
    expect(fn).toMatch(/catch\s*\{/)
    expect(src).toContain('loadError')
  })

  it('Dashboard: 错误态渲染出来, 并且带一个能点的重试按钮', () => {
    const src = code('../../src/views/admin/Dashboard.vue')
    expect(src).toContain('v-if="loadError"')
    expect(src).toContain('class="load-error"')
    expect(src).toContain('role="alert"')
    expect(src).toContain("t('common.retry')")
  })

  it('Dashboard: 错误文案用本地化键, 不透出后端原文', () => {
    expect(code('../../src/views/admin/Dashboard.vue')).toContain('admin.dashboardLoadFailed')
  })
})

describe('D2 — 实时大屏 5 秒轮询不再刷 toast 风暴', () => {
  it('dashboardSummary 声明 skipErrorToast —— 提示权交给页面自己', () => {
    const src = code('../../src/api/admin.ts')
    const fn = fnBody(src, 'dashboardSummary')
    expect(fn).toContain('skipErrorToast: true')
  })

  it('dashboardSummary 与 fetchChatStats 都声明它 —— 页内已有错误行, 拦截器那条是重复的', () => {
    const src = code('../../src/api/admin.ts')
    expect(fnBody(src, 'dashboardSummary')).toContain('skipErrorToast: true')
    expect(fnBody(src, 'fetchChatStats')).toContain('skipErrorToast: true')
  })

  it('只有这两个管理端 api 声明它 —— 其它 admin api 仍由拦截器提示', () => {
    const src = code('../../src/api/admin.ts')
    expect(src.match(/skipErrorToast/g) ?? []).toHaveLength(2)
  })

  it('LiveDashboard: 页面自己给这句提示, 不再依赖拦截器那条通用文案', () => {
    expect(code('../../src/views/admin/LiveDashboard.vue')).toContain('admin.liveLoadFailed')
  })
})

describe('D3 — 助手用量看板的行内错误不再「英文夹中文」', () => {
  it('ChatStats: 走 errorText() —— 中文界面透出后端原文, 其它界面用本地化兜底', () => {
    const src = code('../../src/views/admin/ChatStats.vue')
    expect(src).toContain("from '../../i18n/errorText'")
    expect(src).toContain('errorText(')
    expect(src).toContain('admin.statsLoadFailed')
  })

  it('ChatStats: 不再把后端原文直接当界面文案', () => {
    const src = code('../../src/views/admin/ChatStats.vue')
    expect(src).not.toMatch(/error\.value\s*=\s*e instanceof Error \? e\.message/)
  })

  it('ChatStats: 加载 / 错误 / 空数据的区分仍然完整(别改坏)', () => {
    const src = code('../../src/views/admin/ChatStats.vue')
    expect(src).toContain("error.value = ''")
    expect(src).toContain('stats.value = null')
    expect(src).toContain('loading.value = false')
    expect(src).toContain('v-if="error"')
  })
})

describe('D4 — 管理端跳转目标不再散落字符串字面量', () => {
  const FILES = [
    '../../src/views/admin/AdminHome.vue',
    '../../src/views/admin/Dashboard.vue',
  ]

  it('AdminHome / Dashboard: 源码里不再有 /admin 与 /login 的路径字面量', () => {
    for (const f of FILES) {
      const src = code(f)
      expect(src, `${f} 仍有 /admin 路径字面量`).not.toMatch(/['"]\/admin/)
      expect(src, `${f} 仍有 /login 路径字面量`).not.toMatch(/['"]\/login/)
    }
  })

  it('AdminHome / Dashboard: 改成引用 constants/routes 的常量', () => {
    for (const f of FILES) {
      const src = code(f)
      expect(src, `${f} 没引 constants/routes`).toContain("from '../../constants/routes'")
      expect(src, `${f} 既没用 ROUTE_PATHS 也没用 ROUTE_NAMES`).toMatch(/ROUTE_(PATHS|NAMES)\./)
    }
  })

  it('AdminHome: 退出登录跳 /login 也走常量', () => {
    expect(code('../../src/views/admin/AdminHome.vue')).toContain('ROUTE_NAMES.login')
  })
})

describe('D5 — 管理端有位置感: 侧栏概览项 + 面包屑', () => {
  it('侧栏第一项是「概览」, 指向管理端首页 —— 停在该页时不再无高亮项', () => {
    const src = code('../../src/views/admin/AdminHome.vue')
    const item = src.match(
      /<el-menu-item[^>]*:index="ROUTE_PATHS\.adminHome"[^>]*>([\s\S]*?)<\/el-menu-item>/,
    )
    expect(item, '侧栏没有指向管理端首页的菜单项').not.toBeNull()
    expect(item?.[1], '侧栏首页项的标题不是「概览」').toContain('admin.homeSidebar.overview')
  })

  it('内容区顶部渲染面包屑', () => {
    const src = code('../../src/views/admin/AdminHome.vue')
    expect(src).toContain('<el-breadcrumb')
    expect(src).toContain('adminCrumbs')
  })

  it('adminCrumbs: 管理端各页都给出「管理端 / 当前页」两级', () => {
    expect(adminCrumbs('/admin/dashboard')).toEqual([
      { path: '/admin', labelKey: 'admin.asideBrand' },
      { path: '/admin/dashboard', labelKey: 'admin.dashboardTitle' },
    ])
    expect(adminCrumbs('/admin/chat-stats')).toEqual([
      { path: '/admin', labelKey: 'admin.asideBrand' },
      { path: '/admin/chat-stats', labelKey: 'admin.chatStats.title' },
    ])
  })

  it('adminCrumbs: 首页给的是「概览」而不是空白', () => {
    expect(adminCrumbs('/admin')).toEqual([
      { path: '/admin', labelKey: 'admin.asideBrand' },
      { path: '/admin', labelKey: 'admin.homeSidebar.overview' },
    ])
  })

  it('adminCrumbs: 未知路径只回落根级, 不崩也不显示裸 URL', () => {
    expect(adminCrumbs('/admin/nope')).toEqual([{ path: '/admin', labelKey: 'admin.asideBrand' }])
  })
})

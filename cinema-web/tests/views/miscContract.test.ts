import { describe, it, expect, vi, beforeEach } from 'vitest'
import { code } from '../helpers/source'

/**
 * 零散视图契约 —— 交互流程审查(2026-10-25)批次二/三/四。
 *
 * <p>本仓 vitest 是 {@code environment: 'node'}, 没装 jsdom / @vue/test-utils,
 * 挂不了 SFC —— 视图层的判断只有两条路:
 *   ① 逻辑下沉成纯函数测(见 utils/loginRequiredDetector.test.ts);
 *   ② 对 .vue / .ts 源码做**静态契约断言**(本文件)。
 *
 * <p>静态断言一律**先剥注释再断言** —— 本仓源码注释里大量写着
 * 「原先是怎么写的(为什么这是个坑)」, 拿原文去断言会永远命中, 断言就成了恒真假绿。
 *
 * <p>脆性写在文件头: 断言针对源码文本, 重构时需同步更新。
 */

/** 截取 `from` 标记到 `to` 标记之间的片段; 找不到就判红(避免静默截出空串)。 */
function sliceBetween(src: string, from: string, to: string): string {
  const a = src.indexOf(from)
  expect(a, `找不到起始标记: ${from}`).toBeGreaterThan(-1)
  const b = src.indexOf(to, a + from.length)
  expect(b, `找不到结束标记: ${to}`).toBeGreaterThan(a)
  return src.slice(a, b)
}

// ---------------------------------------------------------------------------
// D1 —— 登录失败只弹一次提示
// ---------------------------------------------------------------------------

/** 把传输层换掉: 本组只关心「登录请求有没有声明自己弹提示」。 */
const { post } = vi.hoisted(() => ({ post: vi.fn() }))

vi.mock('../../src/api/request', () => ({
  default: { post, get: vi.fn() },
}))

import { login, register } from '../../src/api/user'

describe('D1 — 登录失败只弹一次提示', () => {
  beforeEach(() => {
    post.mockClear()
  })

  it('login() 声明 skipErrorToast —— 业务层自己弹提示, 拦截器别再弹一遍', () => {
    login({ username: 'u', password: 'p' })

    expect(post).toHaveBeenCalledWith(
      '/auth/login',
      { username: 'u', password: 'p' },
      { skipErrorToast: true },
    )
  })

  it('register() 不声明 skipErrorToast —— 注册没有业务层提示, 错误必须让用户看见', () => {
    register({ username: 'u', password: 'p' })

    expect(post).toHaveBeenCalledWith('/auth/register', { username: 'u', password: 'p' })
  })

  it('Login.vue 自己兜住所有失败分支 —— 拦截器不再弹的那条, 业务层必须补上', () => {
    const src = code('../../src/views/Login.vue')
    const fn = sliceBetween(src, 'async function handleLogin', 'async function handleRegister')

    // 42900 限流 → 弹窗(更详细的原因)
    expect(fn).toContain('42900')
    expect(fn).toContain('ElMessageBox.alert')
    // 其余失败 → 一条 toast
    expect(fn).toContain('ElMessage.error')
  })
})

// ---------------------------------------------------------------------------
// D2 —— 登录表单校验失败不产生未处理 Promise rejection
// ---------------------------------------------------------------------------

describe('D2 — 登录表单校验失败不产生未处理 rejection', () => {
  it('handleLogin 把 validate() 包在 try/catch 里 —— 校验失败提前 return', () => {
    const src = code('../../src/views/Login.vue')
    const fn = sliceBetween(src, 'async function handleLogin', 'async function handleRegister')

    const tryIdx = fn.indexOf('try {')
    const validateIdx = fn.indexOf('validate()')
    expect(tryIdx).toBeGreaterThan(-1)
    // validate() 必须在 try 之内 —— 否则 Element Plus 的 reject 变成未处理 rejection
    expect(validateIdx).toBeGreaterThan(tryIdx)

    const catchIdx = fn.indexOf('catch', validateIdx)
    expect(catchIdx).toBeGreaterThan(validateIdx)
    expect(fn.slice(catchIdx, catchIdx + 80)).toContain('return')
  })

  it('校验失败时不发请求 —— loading 不该被置起来', () => {
    const src = code('../../src/views/Login.vue')
    const fn = sliceBetween(src, 'async function handleLogin', 'async function handleRegister')

    const catchIdx = fn.indexOf('catch', fn.indexOf('validate()'))
    const loadingIdx = fn.indexOf('loading.value = true')

    expect(catchIdx).toBeGreaterThan(-1)
    // 提前 return 必须发生在 loading 置位之前
    expect(catchIdx).toBeLessThan(loadingIdx)
  })

  it('handleRegister 的 validate reject 处理保持原样(回归锁)', () => {
    const src = code('../../src/views/Login.vue')
    const fn = sliceBetween(src, 'async function handleRegister', 'const barcodeBars')

    const tryIdx = fn.indexOf('try {')
    const validateIdx = fn.indexOf('validate()')
    expect(validateIdx).toBeGreaterThan(tryIdx)

    const catchIdx = fn.indexOf('catch', validateIdx)
    expect(catchIdx).toBeGreaterThan(validateIdx)
    expect(fn.slice(catchIdx, catchIdx + 80)).toContain('return')
  })
})

// ---------------------------------------------------------------------------
// D3 —— 影片详情切日期: 旧错误态不清 + 旧场次残留
// ---------------------------------------------------------------------------

describe('D3 — 切日期清掉旧错误态, 且错误态与场次列表互斥', () => {
  it('loadSessions 命中缓存时也复位 sessionsError', () => {
    const src = code('../../src/views/MovieDetail.vue')
    const fn = sliceBetween(src, 'async function loadSessions', 'watch(selectedDate')

    const cachedIdx = fn.indexOf('if (cached) {')
    expect(cachedIdx).toBeGreaterThan(-1)
    const cachedBranch = fn.slice(cachedIdx, fn.indexOf('const list = await'))

    // 缓存分支提前 return, 但错误态必须一起复位 —— 否则上一个日期的报错一直挂着
    expect(cachedBranch).toContain('sessionsError.value = null')
  })

  it('错误块与场次列表互斥 —— 失败时不再同时渲染上一天的旧场次', () => {
    const src = code('../../src/views/MovieDetail.vue')

    expect(src).toMatch(/<div\s+v-if="sessionsError[^"]*"/)
    // v-else 与错误块/空态构成互斥链: 失败时只出错误块, 不出旧列表
    expect(src).toMatch(/<div\s+v-else[^>]*class="session-list"/)
  })

  it('切日期仍只由 watch 驱动(回归锁, 别把 @change 绑回去)', () => {
    const src = code('../../src/views/MovieDetail.vue')

    expect(src).not.toContain('@change="loadSessions"')
    expect(src).toContain('watch(selectedDate')
  })
})

// ---------------------------------------------------------------------------
// D4 —— 首页搜索框的放大镜图标
// ---------------------------------------------------------------------------

describe('D4 — 首页搜索框的放大镜图标', () => {
  it('Search 图标被 import 进来 —— EP 不全局注册图标, 不 import 就是个空位', () => {
    const src = code('../../src/views/Home.vue')

    expect(src).toMatch(/import\s*\{[^}]*\bSearch\b[^}]*\}\s*from\s*'@element-plus\/icons-vue'/)
  })

  it('prefix-icon 绑的是组件本身, 不是字符串', () => {
    const src = code('../../src/views/Home.vue')

    expect(src).toContain(':prefix-icon="Search"')
    expect(src).not.toContain(`:prefix-icon="'Search'"`)
  })
})

// ---------------------------------------------------------------------------
// D5 —— Hero 的影片数口径: 筛选后不该还说「热映影片」
// ---------------------------------------------------------------------------

describe('D5 — Hero 的影片数口径', () => {
  it('两个口径都在: 筛选时「部匹配影片」, 无筛选时「部热映影片」', () => {
    const src = code('../../src/views/Home.vue')
    const fn = sliceBetween(src, 'const heroStatLabel', 'const barcodeBars')

    expect(fn).toContain('home.statMoviesFiltered')
    expect(fn).toContain('home.statMovies')
  })

  it('筛选条件覆盖 keyword / genre / region 三个维度', () => {
    const src = code('../../src/views/Home.vue')
    const fn = sliceBetween(src, 'const heroStatLabel', 'const barcodeBars')

    expect(fn).toContain('keyword.value')
    expect(fn).toContain('genre.value')
    expect(fn).toContain('region.value')
  })

  it('Hero 标签不再写死 home.statMovies —— 它得跟着筛选走', () => {
    const src = code('../../src/views/Home.vue')
    const tpl = src.slice(src.indexOf('<template>'))

    expect(tpl).toContain('heroStatLabel')
    expect(tpl).not.toContain(`t('home.statMovies')`)
  })

  it('筛选栏的 filterMeta 保持筛选结果口径(回归锁, 别动)', () => {
    const src = code('../../src/views/Home.vue')

    expect(src).toContain(`t('home.filterMeta', { count: movies.length })`)
  })
})

// ---------------------------------------------------------------------------
// D7 —— 404 的「返回上一页」不能把用户带离本站
// ---------------------------------------------------------------------------

describe('D7 — 404 的「返回上一页」不能把用户带离本站', () => {
  it('判据取「站内上一页」(history.state.back), 不用 history.length', () => {
    const src = code('../../src/views/NotFound.vue')

    expect(src).toMatch(/history\.state\??\.back/)
    // history.length 连站外历史一起数 —— 从外链进来它也是 2, 据此判断会把用户带离本站
    expect(src).not.toContain('history.length')
  })

  it('「返回上一页」按钮被条件包住 —— 没有站内上一页就不显示', () => {
    const src = code('../../src/views/NotFound.vue')

    expect(src).toMatch(/<el-button[^>]*v-if="[^"]*"[^>]*@click="router\.back\(\)"/)
  })

  it('「返回首页」始终可用', () => {
    const src = code('../../src/views/NotFound.vue')

    expect(src).toContain(`t('notFound.backHome')`)
  })
})

// ---------------------------------------------------------------------------
// P3-10 —— 首页搜索失败不能退化成「暂无匹配影片」
// ---------------------------------------------------------------------------

describe('P3-10 — 首页列表加载失败 ≠ 没有影片', () => {
  it('Home: loadMovies 有 catch, 失败不产生未处理的 rejection', () => {
    const src = code('../../src/views/Home.vue')
    const fn = src.slice(src.indexOf('async function loadMovies'))
    const body = fn.slice(0, fn.indexOf('finally'))

    expect(body).toMatch(/catch\s*\{/)
  })

  it('Home: 有错误态与重试按钮, 且与「暂无匹配影片」互斥', () => {
    const src = code('../../src/views/Home.vue')
    const tpl = src.slice(src.indexOf('<template>'))

    expect(src).toContain('loadError')
    // 文案在 script 里翻译, 模板渲染的是 ref 的值
    expect(src).toContain(`t('home.loadFailed')`)
    expect(tpl).toContain('{{ loadError }}')
    expect(tpl).toContain(`t('common.retry')`)
    // 错误态必须排在空状态前面 —— 失败时不能同时显示「暂无匹配影片」
    expect(tpl.indexOf('v-else-if="loadError"')).toBeGreaterThan(-1)
    expect(tpl.indexOf('v-else-if="loadError"')).toBeLessThan(
      tpl.indexOf(`t('home.empty')`),
    )
  })
})

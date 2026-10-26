import { describe, it, expect } from 'vitest'
import { code } from '../helpers/source'

/**
 * 产品主页(`/`)的静态契约(2026-10-26)。
 *
 * <p>**为什么锁这些**: 主页从「一个挂在 `/landing` 的孤岛」变成了**站点默认入口**,
 * 于是它身上多了两条硬约束 ——
 *
 * <ol>
 *   <li>hero 主 CTA 必须指向**浏览页**(`/movies`), 不能自指到本页
 *       (自指的话点了等于没点, 访客会以为按钮坏了);</li>
 *   <li>登录入口只对**未登录**的人显示 —— 已登录用户看到「登录 / 注册」是噪声。</li>
 * </ol>
 *
 * <p>另外两条是主题纪律: 主页**不属于任何身份色域**
 * (`themeTokens.test.ts` 的域映射里没有 `views/Landing.vue`), 所以它里面
 * 不许出现 `--scene-*`; 也不许写死色值 —— 改色只改 token。
 *
 * <p>⚠️ 用 `code()` 先剥注释 —— 否则断言会命中注释里的字样, 变成恒真假绿。
 */
describe('views/Landing.vue — 产品主页契约', () => {
  const src = code('../../src/views/Landing.vue')

  it('hero 主 CTA 指向影片浏览页, 不自指到本页', () => {
    expect(src).toContain(':to="ROUTE_PATHS.movies"')
    expect(src, '主页不该再自指 —— 那等于一个点了没反应的按钮').not.toContain(':to="ROUTE_PATHS.home"')
  })

  it('登录 CTA 只在未登录时显示', () => {
    expect(src).toContain('landing.ctaLogin')
    expect(src).toContain('v-if="!userStore.isLogin"')
    expect(src).toContain(':to="ROUTE_PATHS.login"')
  })

  it('身份色域纪律: 主页不属于任何域, 不许用 --scene-*', () => {
    expect(src).not.toContain('var(--scene-')
  })

  it('颜色纪律: 不许写死色值(改色只改 token)', () => {
    expect(src.match(/#[0-9a-fA-F]{6}\b/g)).toBeNull()
    expect(src).not.toMatch(/rgba\(\s*\d/)
  })
})

describe('App.vue — 顶栏导航契约', () => {
  const src = code('../../src/App.vue')

  it('品牌链接走常量(根路径 = 产品主页)', () => {
    expect(src).toContain(':to="ROUTE_PATHS.home"')
    expect(src).not.toContain('to="/"')
  })

  it('「进入购票」chip 所有人可见, 直通浏览页', () => {
    expect(src).toContain('app.chipBrowse')
    expect(src).toContain('router.push(ROUTE_PATHS.movies)')
    // 匿名访客也要看得到 —— 不能被 v-if="userStore.isLogin" 挡住
    expect(src).not.toMatch(/v-if="userStore\.isLogin"[^>]*\n?[^>]*app\.chipBrowse/)
  })

  it('顶栏不再有路径字面量', () => {
    expect(src).not.toContain("router.push('/login')")
    expect(src).not.toContain("router.push('/orders')")
    expect(src).not.toContain("router.push('/admin')")
  })
})

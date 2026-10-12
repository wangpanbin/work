import { describe, it, expect } from 'vitest'
import {
  buildTrendChart,
  tooltipPlacement,
  CHART_WIDTH,
  CHART_HEIGHT,
  PAD_LEFT,
  PAD_TOP,
  BASELINE_Y,
  TIP_W,
} from '../../../src/views/admin/trendChart'

/**
 * trendChart.test.ts — 经营看板「7 日票房趋势」折线图坐标映射契约。
 *
 * <p><b>本文件测的是行为而非写法</b>:坐标映射已下沉到 `views/admin/trendChart.ts` 纯函数,
 * 因此这里直接调用并断言输出几何量,而不是正则扫 `Dashboard.vue` 源码。
 * 上一版(柱状图)只能静态扫 —— 因为那时 `height: amount/max*180` 的算式还埋在 SFC 模板里,
 * 而本仓库 vitest 是 `environment: 'node'`、没装 jsdom / @vue/test-utils,挂不上组件(见 AGENTS.md)。
 *
 * <p><b>回归目标</b>:2026-10-12 改造前的柱状图有两个缺陷,本文件把修复钉住 ——
 * ① 全零数据时 `maxTrend()` 返回 1,若该值被拿去显示就会谎报「¥1.00」;
 * ② 除零 / 脏数据会让 SVG path 里出现 `NaN`,渲染成空白图。
 */

/** 7 天演示数据:前 5 天 0,后 2 天各 79 —— 与改造前截图里的形态一致 */
function demo7() {
  return [
    { date: '2026-10-06', amount: 0 },
    { date: '2026-10-07', amount: 0 },
    { date: '2026-10-08', amount: 0 },
    { date: '2026-10-09', amount: 0 },
    { date: '2026-10-10', amount: 0 },
    { date: '2026-10-11', amount: 79 },
    { date: '2026-10-12', amount: 79 },
  ]
}

describe('buildTrendChart — 正常 7 点数据', () => {
  it('产出 7 个点,x 严格递增且都落在绘图区内', () => {
    const m = buildTrendChart(demo7())
    expect(m.empty).toBe(false)
    expect(m.points).toHaveLength(7)

    for (let i = 1; i < m.points.length; i++) {
      expect(m.points[i].x).toBeGreaterThan(m.points[i - 1].x)
    }
    for (const p of m.points) {
      expect(p.y).toBeGreaterThanOrEqual(PAD_TOP)
      expect(p.y).toBeLessThanOrEqual(BASELINE_Y)
    }
  })

  it('折线是 M + 6 个 L,面积收口到基线并以 Z 闭合,两者都不含 NaN', () => {
    const m = buildTrendChart(demo7())

    expect(m.linePath.startsWith('M')).toBe(true)
    expect(m.linePath.match(/L/g)).toHaveLength(6)
    expect(m.areaPath.endsWith('Z')).toBe(true)

    // 回归锁:除零或脏数据会让 path 变成 "M24,NaN",整张图渲染为空白
    expect(m.linePath).not.toContain('NaN')
    expect(m.areaPath).not.toContain('NaN')
    expect(m.gridLines).toHaveLength(3)
    expect(m.gridLines[0]).toBe(PAD_TOP)
    expect(m.gridLines[2]).toBe(BASELINE_Y)
  })

  it('日期刻度取 MM-DD,tooltip 用完整 ISO 日期', () => {
    const m = buildTrendChart(demo7())
    expect(m.points[0].label).toBe('10-06')
    expect(m.points[6].label).toBe('10-12')
    expect(m.points[6].date).toBe('2026-10-12')
  })

  it('max 与 scaleMax 一致时相等', () => {
    const m = buildTrendChart(demo7())
    expect(m.max).toBe(79)
    expect(m.scaleMax).toBe(79)
  })
})

describe('buildTrendChart — 边界与脏数据', () => {
  it('空数组标记 empty,各几何量均为空', () => {
    const m = buildTrendChart([])
    expect(m.empty).toBe(true)
    expect(m.points).toHaveLength(0)
    expect(m.linePath).toBe('')
    expect(m.areaPath).toBe('')
    expect(m.max).toBe(0)
  })

  it('单点:不抛错,x 居中,linePath 只有 M 没有 L', () => {
    const m = buildTrendChart([{ date: '2026-10-12', amount: 79 }])
    expect(m.points).toHaveLength(1)
    expect(m.points[0].x).toBe(CHART_WIDTH / 2)
    expect(m.linePath.startsWith('M')).toBe(true)
    expect(m.linePath).not.toContain('L')
    expect(m.linePath).not.toContain('NaN')
  })

  /**
   * 全零是最容易出错的场景:旧 `maxTrend()` 是 `Math.max(..., 1)`,
   * 直接拿去显示会把「7 天都是 0」显示成 ¥1.00。
   * 这里把 max(显示用,可为 0) 与 scaleMax(算坐标用,恒 >=1) 钉死。
   */
  it('全零数据:max 为 0 而 scaleMax 为 1,所有点贴在基线', () => {
    const rows = Array.from({ length: 7 }, (_, i) => ({
      date: `2026-10-${String(i + 6).padStart(2, '0')}`,
      amount: 0,
    }))
    const m = buildTrendChart(rows)

    expect(m.max).toBe(0)
    expect(m.scaleMax).toBe(1)
    for (const p of m.points) {
      expect(p.y).toBe(BASELINE_Y)
    }
    expect(m.linePath).not.toContain('NaN')
  })

  it('NaN / Infinity 金额归一为 0,不产生 NaN 坐标', () => {
    const m = buildTrendChart([
      { date: '2026-10-11', amount: Number.NaN },
      { date: '2026-10-12', amount: Number.POSITIVE_INFINITY },
    ])

    expect(m.points[0].amount).toBe(0)
    expect(m.points[1].amount).toBe(0)
    expect(m.linePath).not.toContain('NaN')
    expect(m.areaPath).not.toContain('NaN')
    for (const p of m.points) {
      expect(Number.isFinite(p.x)).toBe(true)
      expect(Number.isFinite(p.y)).toBe(true)
    }
  })

  it('负数金额被 clamp 在绘图区内,不越出顶部', () => {
    const m = buildTrendChart([
      { date: '2026-10-11', amount: -500 },
      { date: '2026-10-12', amount: 79 },
    ])

    expect(m.points[0].y).toBeGreaterThanOrEqual(PAD_TOP)
    expect(m.points[0].y).toBeLessThanOrEqual(BASELINE_Y)
    expect(m.linePath).not.toContain('NaN')
  })
})

describe('tooltipPlacement — 定位与翻转', () => {
  it('最左点不越过左边界,最右点不越过右边界', () => {
    const m = buildTrendChart(demo7())

    const first = tooltipPlacement(m.points[0])
    const last = tooltipPlacement(m.points[6])

    expect(first.leftPct).toBeGreaterThanOrEqual(0)
    expect(last.leftPct).toBeLessThanOrEqual(100)
    // 左端点会被 clamp 贴边 —— 否则 tooltip 左半截跑到面板外
    expect(first.leftPct).toBe(0)
    // 右端点 clamp 到 CHART_WIDTH - TIP_W(百分比按实现约定保留 2 位小数)
    expect(last.leftPct).toBeCloseTo((100 * (CHART_WIDTH - TIP_W)) / CHART_WIDTH, 2)
  })

  it('顶部点翻到点下方,底部点保持在点上方', () => {
    const m = buildTrendChart(demo7())

    // 79 落在顶部(PAD_TOP)
    expect(m.points[6].y).toBe(PAD_TOP)
    expect(tooltipPlacement(m.points[6]).below).toBe(true)

    // 0 落在基线(BASELINE_Y)
    expect(m.points[0].y).toBe(BASELINE_Y)
    expect(tooltipPlacement(m.points[0]).below).toBe(false)
  })

  it('返回的百分比落在 0~100 之间,可直接拼成 CSS 定位值', () => {
    const m = buildTrendChart(demo7())
    for (const p of m.points) {
      const tip = tooltipPlacement(p)
      expect(tip.leftPct).toBeGreaterThanOrEqual(0)
      expect(tip.leftPct).toBeLessThanOrEqual(100)
      expect(tip.topPct).toBeGreaterThanOrEqual(0)
      // 翻到下方时 top 是 p.y + 10,最高也就是 BASELINE_Y + 10
      expect(tip.topPct).toBeLessThanOrEqual(((BASELINE_Y + 10) / CHART_HEIGHT) * 100)
    }
  })
})

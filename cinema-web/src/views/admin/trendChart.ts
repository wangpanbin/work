/**
 * trendChart.ts — 经营看板「7 日票房趋势」折线图的坐标映射纯函数。
 *
 * <p>**为什么独立成文件**:原实现把柱高算式内联在 `Dashboard.vue` 的模板里,
 * 而本仓库 vitest 是 `environment: 'node'`、没装 jsdom / @vue/test-utils,
 * 挂不上 SFC 组件 —— 逻辑埋在模板里就只能静态扫源码做契约断言(脆)。
 * 下沉成纯函数后,本模块的行为可以被 `tests/views/admin/trendChart.test.ts`
 * 直接调用验证。范式与 `views/session/constants.ts` 一致。
 *
 * <p>**本模块刻意零依赖**:不引图表库(仓库 package.json / node_modules / lockfile
 * 三处均无 echarts / chart.js / d3),颜色全部由 `Dashboard.vue` 的 CSS 变量提供。
 *
 * <p>**i18n 约束**:`tests/i18n/key-coverage.test.ts` 会扫描 `src` 下 **.vue 与 .ts**
 * 里的 `t('a.b')` 调用并断言键存在。本模块是纯几何计算,**不得出现任何翻译调用**。
 */

/** viewBox 宽高 —— SVG 靠 `width:100%; height:auto` 等比缩放到容器,无需 JS 测量 */
export const CHART_WIDTH = 720
export const CHART_HEIGHT = 200

export const PAD_LEFT = 24
export const PAD_RIGHT = 24
export const PAD_TOP = 16
export const PAD_BOTTOM = 30

const INNER_W = CHART_WIDTH - PAD_LEFT - PAD_RIGHT // 672
const INNER_H = CHART_HEIGHT - PAD_TOP - PAD_BOTTOM // 154

/** 绘图区基线(y = 0 的位置),也是面积填充的下沿 */
export const BASELINE_Y = PAD_TOP + INNER_H // 170

/** tooltip 逻辑尺寸(viewBox 单位) */
export const TIP_W = 120
export const TIP_H = 46
const TIP_GAP = 10
/** 允许 tooltip 探出绘图区顶部的余量:再往上就翻到点的下方 */
const TIP_TOP_ALLOWANCE = 8

export interface TrendPointInput {
  date: string
  amount: number
}

export interface ChartPoint {
  /** viewBox 坐标 */
  x: number
  y: number
  /** 原始 ISO 日期,tooltip 用完整日期 */
  date: string
  /** x 轴刻度文案 = date.slice(5),即 MM-DD */
  label: string
  /** 归一后的金额(非有限值已折成 0) */
  amount: number
}

export interface TooltipPlacement {
  /** 相对容器宽度的百分比 —— 与 viewBox 坐标 1:1 对应 */
  leftPct: number
  /** 相对容器高度的百分比 */
  topPct: number
  /** true = 渲染在数据点下方(顶部点自动翻转) */
  below: boolean
}

export interface TrendChartModel {
  points: ChartPoint[]
  /** "M24,170 L136,170 …" */
  linePath: string
  /** linePath + 收口到基线 + "Z" */
  areaPath: string
  /** 三条水平网格线的 y:顶部 / 中值 / 基线 */
  gridLines: number[]
  /** 实际数据最大值 —— **只用于显示,可能为 0** */
  max: number
  /** Math.max(max, 1) —— **只用于算坐标,防除零** */
  scaleMax: number
  empty: boolean
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(Math.max(n, lo), hi)
}

/** 坐标保留 2 位小数,避免 112.00000000000001 这类浮点尾巴撑大 DOM 属性 */
function r2(n: number): number {
  return Math.round(n * 100) / 100
}

/** 后端脏数据(NaN / Infinity / null)一律折成 0,杜绝 NaN 坐标污染 SVG path */
function normalizeAmount(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

function xAt(index: number, total: number): number {
  // 单点时居中,否则贴左边缘会显得图表偏斜
  if (total <= 1) return CHART_WIDTH / 2
  return PAD_LEFT + (INNER_W * index) / (total - 1)
}

function yAt(amount: number, scaleMax: number): number {
  // clamp 是必须的:负数金额(退票冲抵等)会把线画到绘图区外面去
  return clamp(PAD_TOP + INNER_H * (1 - amount / scaleMax), PAD_TOP, BASELINE_Y)
}

/**
 * 把 `weeklyTrend` 映射成 SVG 折线图所需的全部几何量。
 *
 * <p>输入来自 `DashboardSummary.weeklyTrend`,后端 `DashboardTrendQuery.weeklyTrend()`
 * 恒返回 7 个点、日期升序、缺失日补 0 —— 但本函数对任意长度(含 0 / 1)都成立。
 */
export function buildTrendChart(rows: TrendPointInput[]): TrendChartModel {
  const safeRows = Array.isArray(rows) ? rows : []
  const amounts = safeRows.map((row) => normalizeAmount(row ? row.amount : 0))

  /**
   * `max` 与 `scaleMax` 必须分离。
   *
   * 全零数据时 dataMax = 0,右上角应显示 ¥0.00 —— 复用旧 `maxTrend()` 那种
   * `Math.max(..., 1)` 的单一返回值做显示,会凭空显示成 ¥1.00,谎报有 1 元票房。
   * 所以:max 供显示(可为 0),scaleMax 供除法(恒 >= 1)。
   */
  const max = amounts.length > 0 ? Math.max(...amounts) : 0
  const scaleMax = Math.max(max, 1)

  const points: ChartPoint[] = amounts.map((amount, index) => {
    const date = safeRows[index] && safeRows[index].date ? String(safeRows[index].date) : ''
    return {
      x: r2(xAt(index, amounts.length)),
      y: r2(yAt(amount, scaleMax)),
      date,
      label: date.slice(5),
      amount,
    }
  })

  const linePath = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x},${p.y}`).join(' ')

  let areaPath = ''
  if (points.length > 0) {
    const first = points[0]
    const last = points[points.length - 1]
    const base = r2(BASELINE_Y)
    areaPath = `${linePath} L${last.x},${base} L${first.x},${base} Z`
  }

  const gridLines = [PAD_TOP, r2(PAD_TOP + INNER_H / 2), r2(BASELINE_Y)]

  return {
    points,
    linePath,
    areaPath,
    gridLines,
    max,
    scaleMax,
    empty: points.length === 0,
  }
}

/**
 * tooltip 定位 —— 按**左上角**对齐,配合 CSS 的 `translateY(-100%)` / `translateY(0)`。
 *
 * <p>返回值是百分比而非 viewBox 绝对值:`.trend-chart` 无 padding、SVG 是唯一子元素
 * 且 `height:auto` 保持 viewBox 比例,故百分比与 viewBox 坐标 1:1 对应。
 * x 在 viewBox 空间 clamp,保证左边界与右边界都不出血。
 */
export function tooltipPlacement(p: ChartPoint): TooltipPlacement {
  const boxX = clamp(p.x - TIP_W / 2, 0, CHART_WIDTH - TIP_W)
  const above = p.y - TIP_H - TIP_GAP
  const below = above < PAD_TOP - TIP_TOP_ALLOWANCE
  const boxY = below ? p.y + TIP_GAP : above

  return {
    leftPct: r2((100 * boxX) / CHART_WIDTH),
    topPct: r2((100 * boxY) / CHART_HEIGHT),
    below,
  }
}

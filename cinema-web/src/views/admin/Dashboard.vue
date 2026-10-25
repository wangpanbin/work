<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  dashboardSummary,
  exportRevenue,
  type DashboardSummary,
  type RevenueExportQuery,
} from '../../api/admin'
import { downloadBlob } from '../../utils/download'
import {
  DEFAULT_EXPORT_PRESET,
  EXPORT_PRESETS,
  type ExportPreset,
} from './constants'
import {
  buildTrendChart,
  tooltipPlacement,
  CHART_WIDTH,
  CHART_HEIGHT,
  PAD_LEFT,
  PAD_RIGHT,
  PAD_TOP,
} from './trendChart'

const router = useRouter()
const { t } = useI18n()
const data = ref<DashboardSummary | null>(null)
const loading = ref(false)

// ====== T5: 导出对话框 ======
const exportDialogVisible = ref(false)
const exportMode = ref<ExportPreset>(DEFAULT_EXPORT_PRESET)
const exportRange = ref<[string, string] | null>(null)
const exportError = ref<string | null>(null)
const exportLoading = ref(false)

function toIso(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}

/** 由 exportMode + exportRange 计算出 [from, to] 两个 ISO 日期 */
const computedRange = computed<{ from: string; to: string }>(() => {
  const preset = EXPORT_PRESETS[exportMode.value]
  if (!preset.compute) {
    // custom: 用用户在 daterange 选的范围; 未选时回退 7d
    if (exportRange.value && exportRange.value.length === 2) {
      return { from: exportRange.value[0], to: exportRange.value[1] }
    }
    const fallback = EXPORT_PRESETS['7d'].compute!(new Date())!
    return { from: toIso(fallback.from), to: toIso(fallback.to) }
  }
  const { from, to } = preset.compute(new Date())
  return { from: toIso(from), to: toIso(to) }
})

function openExportDialog() {
  exportError.value = null
  exportDialogVisible.value = true
}

async function doExport() {
  exportError.value = null
  exportLoading.value = true
  const r = computedRange.value
  try {
    const query: RevenueExportQuery = { from: r.from, to: r.to, mode: exportMode.value === 'custom' ? 'custom' : 'preset' }
    const blob = await exportRevenue(query)
    const filename = `revenue_${r.from}_to_${r.to}.xlsx`
    downloadBlob(blob, filename)
    ElMessage.success(t('admin.exportSuccess', { name: filename }))
    exportDialogVisible.value = false
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : t('admin.exportFailed')
    exportError.value = msg
    ElMessage.error(msg)
  } finally {
    exportLoading.value = false
  }
}

// ====== 原有 dashboard 逻辑 ======
async function load() {
  loading.value = true
  try {
    data.value = await dashboardSummary()
  } finally {
    loading.value = false
  }
}

function fmtAmount(n: number | undefined) {
  if (n == null) return '0.00'
  return Number(n).toFixed(2)
}

function fmtRate(r: number | undefined) {
  if (r == null) return '0%'
  return (Number(r) * 100).toFixed(1) + '%'
}

// ====== 7 日票房趋势折线图 ======
/** hover 中的数据点下标; -1 = 未 hover。纯 UI 态,无持久化 */
const hoverIndex = ref(-1)

/** 坐标映射下沉到 views/admin/trendChart.ts 纯函数,便于单测(挂不了 SFC) */
const chart = computed(() => buildTrendChart(data.value?.weeklyTrend ?? []))

/**
 * 当前 hover 的点。load() 刷新后点数可能变化使 hoverIndex 越界,
 * 故此处显式判空 —— tsconfig 未开 noUncheckedIndexedAccess,索引越界不会报类型错。
 */
const hovered = computed(() => {
  const i = hoverIndex.value
  return i >= 0 && i < chart.value.points.length ? chart.value.points[i] : null
})

const hoveredTip = computed(() => (hovered.value ? tooltipPlacement(hovered.value) : null))

function maxMovie(): number {
  if (!data.value?.topMovies?.length) return 0
  return Math.max(...data.value.topMovies.map((m) => Number(m.revenue ?? 0)), 1)
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="dashboard">
    <div class="page-header">
      <!-- 这三处原本是硬编码中文 + emoji, 完全绕过 i18n ——
           切到英文界面时这里仍显示中文。改回字典键。 -->
      <h2>{{ t('admin.dashboardTitle') }}</h2>
      <div class="header-actions">
        <el-button type="primary" @click="openExportDialog">{{ t('admin.exportBtn') }}</el-button>
        <el-button @click="router.push('/admin')" plain>{{ t('admin.backToHome') }}</el-button>
      </div>
    </div>

    <template v-if="data">
      <!-- 4 卡片 -->
      <div class="cards">
        <div class="card card-reveal card-reveal--plain gold">
          <div class="card-label">{{ t('admin.cardTodayRevenue') }}</div>
          <div class="card-value">¥{{ fmtAmount(data.todayRevenue) }}</div>
        </div>
        <div class="card card-reveal card-reveal--plain">
          <div class="card-label">{{ t('admin.cardTodayOrders') }}</div>
          <div class="card-value">{{ data.todayOrders ?? 0 }}</div>
          <div class="card-sub">{{ t('admin.cardTodaySub', { paid: data.todayPaid ?? 0, seats: data.todayPendingSeats ?? 0 }) }}</div>
        </div>
        <div class="card card-reveal card-reveal--plain">
          <div class="card-label">{{ t('admin.cardCancelled') }}</div>
          <div class="card-value">{{ data.todayCancelled ?? 0 }}</div>
        </div>
        <div class="card card-reveal card-reveal--plain">
          <div class="card-label">{{ t('admin.cardRefunded') }}</div>
          <div class="card-value">{{ data.todayRefunded ?? 0 }}</div>
        </div>
      </div>

      <!-- 7 日票房趋势(折线)。0 值照实连线 —— 0 是真实业务事实,隐藏等于对数据撒谎 -->
      <div class="panel panel-trend">
        <h3>{{ t('admin.trendTitle') }}</h3>
        <div v-if="!chart.empty" class="trend-chart">
          <svg
            :viewBox="`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`"
            preserveAspectRatio="xMidYMid meet"
            class="trend-svg"
          >
            <defs>
              <!-- 面积渐变:印色 stamp -> wash,不新增 CSS 变量、不硬编码 rgba -->
              <linearGradient id="trendFadeGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" style="stop-color: var(--accent-stamp)" />
                <stop offset="100%" style="stop-color: var(--accent-wash)" />
              </linearGradient>
            </defs>
            <line
              v-for="(y, i) in chart.gridLines"
              :key="'g' + i"
              :x1="PAD_LEFT"
              :x2="CHART_WIDTH - PAD_RIGHT"
              :y1="y"
              :y2="y"
              class="grid-line"
            />
            <path :d="chart.areaPath" class="trend-area" />
            <path :d="chart.linePath" class="trend-line" />
            <circle v-for="(p, i) in chart.points" :key="'d' + i" :cx="p.x" :cy="p.y" :r="3" class="trend-dot" />
            <text
              v-for="(p, i) in chart.points"
              :key="'t' + i"
              :x="p.x"
              :y="CHART_HEIGHT - 10"
              class="trend-tick"
              text-anchor="middle"
            >{{ p.label }}</text>
            <!-- 量级标注用 chart.max(实际最大值),不是 scaleMax —— 全零时才不会显示成 ¥1.00 -->
            <text :x="CHART_WIDTH - PAD_RIGHT" :y="PAD_TOP - 6" class="trend-max" text-anchor="end">¥{{ fmtAmount(chart.max) }}</text>
            <!-- hover 热区必须排在圆点之后(SVG 后绘制在上层),否则 3px 圆点几乎点不中 -->
            <circle
              v-for="(p, i) in chart.points"
              :key="'h' + i"
              :cx="p.x"
              :cy="p.y"
              :r="14"
              class="trend-hit"
              @mouseenter="hoverIndex = i"
              @mouseleave="hoverIndex = -1"
            />
          </svg>
          <div
            v-if="hovered && hoveredTip"
            class="trend-tip"
            :class="{ below: hoveredTip.below }"
            :style="{ left: hoveredTip.leftPct + '%', top: hoveredTip.topPct + '%' }"
          >
            <span class="tip-date">{{ hovered.date }}</span>
            <span class="tip-amount">¥{{ fmtAmount(hovered.amount) }}</span>
          </div>
        </div>
        <div v-else class="empty">{{ t('admin.emptyTrend') }}</div>
      </div>

      <!-- TOP 5 影片 -->
      <div class="panel panel-rank">
        <h3>{{ t('admin.topMoviesTitle') }}</h3>
        <div v-if="!data.topMovies.length" class="empty">{{ t('admin.emptyMovies') }}</div>
        <div v-else class="movie-bar">
          <div v-for="(m, i) in data.topMovies" :key="i" class="movie-row">
            <div class="rank">{{ i + 1 }}</div>
            <div class="movie-name">{{ m.title }}</div>
            <div class="movie-bar-wrap">
              <!-- revenue 缺失时按 0 算: Number(undefined) 是 NaN, 拼成 "NaN%" 会被浏览器
                   当作非法样式整条丢弃, 条形图于是静默停在默认宽度, 看上去像「这影片没票房」 -->
              <div class="movie-bar-fill" :style="{ width: (Number(m.revenue ?? 0) / maxMovie() * 100) + '%' }"></div>
            </div>
            <div class="movie-rev">¥{{ fmtAmount(m.revenue) }} · {{ m.orders + ' ' + t('admin.ordersUnit') }}</div>
          </div>
        </div>
      </div>

      <!-- 上座率 TOP 10 场次 -->
      <div class="panel panel-rate">
        <h3>{{ t('admin.topSessionsTitle') }}</h3>
        <el-table v-if="data.topSessions.length" :data="data.topSessions" stripe size="small">
          <el-table-column prop="movieTitle" :label="t('admin.colMovie')" />
          <el-table-column prop="hallName" :label="t('admin.colHall')" width="120" />
          <el-table-column prop="startTime" :label="t('admin.colStartTime')" width="170" />
          <el-table-column :label="t('admin.colOccupancy')" width="200">
            <template #default="{ row }">
              <div class="rate-bar">
                <div class="rate-fill" :style="{ width: (Number(row.occupancyRate) * 100) + '%' }"></div>
                <span class="rate-text">{{ fmtRate(row.occupancyRate) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty">{{ t('admin.emptySessions') }}</div>
      </div>
    </template>

    <!-- T5: 导出对话框 -->
    <el-dialog v-model="exportDialogVisible" :title="t('admin.exportDialogTitle')" width="480px" :close-on-click-modal="false">
      <div class="export-form">
        <div class="form-row">
          <span class="form-label">{{ t('admin.exportRange') }}</span>
          <el-radio-group v-model="exportMode">
            <el-radio-button
              v-for="(def, key) in EXPORT_PRESETS"
              :key="key"
              :value="key"
            >{{ t(def.labelKey) }}</el-radio-button>
          </el-radio-group>
        </div>

        <div v-if="exportMode === 'custom'" class="form-row">
          <span class="form-label">{{ t('admin.exportDateRange') }}</span>
          <el-date-picker
            v-model="exportRange"
            type="daterange"
            value-format="yyyy-MM-DD"
            :range-separator="t('admin.exportTo')"
            :start-placeholder="t('admin.exportStart')"
            :end-placeholder="t('admin.exportEnd')"
            :clearable="false"
            style="width: 100%"
          />
        </div>

        <div class="form-row preview">
          <span class="form-label">{{ t('admin.exportWillExport') }}</span>
          <span class="preview-text">{{ computedRange.from }} – {{ computedRange.to }}</span>
        </div>

        <div v-if="exportError" class="form-error">{{ exportError }}</div>
      </div>

      <template #footer>
        <el-button @click="exportDialogVisible = false">{{ t('admin.exportCancel') }}</el-button>
        <el-button type="primary" :loading="exportLoading" @click="doExport">{{ t('admin.exportOk') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dashboard {
  animation: fadeInUp 0.5s ease;
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}
.page-header h2 { margin: 0; font-family: var(--font-display); font-size: 22px; }
.header-actions { display: flex; gap: 12px; align-items: center; }
@media (max-width: 768px) {
  .page-header h2 { font-size: 18px; }
}

/* 看板根容器改 12 列栅格: 趋势图与影片排行并排, 上座率表通栏。
   控制台该有的密度 —— 三块面板竖着排会把首屏拉成一整屏图表。 */
.dashboard {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 16px;
  align-items: start;
}
.dashboard > .page-header,
.dashboard > .cards,
.dashboard > .panel-rate {
  grid-column: span 12;
}
.dashboard > .panel-trend {
  grid-column: span 8;
}
.dashboard > .panel-rank {
  grid-column: span 4;
}
/* 栅格自带 gap, 面板与卡片不再各自留外边距 */
.dashboard > .cards,
.dashboard > .panel {
  margin-bottom: 0;
}

.cards {
  display: grid;
  /* 头条卡(今日票房)更宽: 层级靠布局, 不靠颜色 */
  grid-template-columns: 1.5fr 1fr 1fr 1fr;
  gap: 12px;
}
.card {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 16px;
  position: relative;
  overflow: hidden;
}
/* 头条卡: 去掉蓝底, 改用一道左侧印色条 —— 层级由**结构**承担,
   印色留给「可交互 + 图表」, 数字一律用墨色(数据墨)。 */
.card.gold {
  border-color: var(--rule-strong);
  border-left: 3px solid var(--accent);
}
.card-label {
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 8px;
  letter-spacing: 1px;
}
/* KPI 数值: 等宽 + tabular-nums, 5s 轮询刷新时数字不左右跳 */
.card-value {
  font-size: 30px;
  font-weight: 700;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}
/* 头条卡的数字同样用墨色 —— 字号与印色条已经把它标成头条, 数字再上蓝就成了双重强调 */
.card.gold .card-value {
  color: var(--ink);
  font-size: 34px;
}
.card-sub {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 8px;
}

/* 反馈层: 卡片悬停只提亮描边, 不位移 —— 控制台不做抬升 */
/* 控制台的悬停(抬 2px + 描边变强, **不改色**)走 main.css 的 .card-reveal--plain ——
   管理端的颜色纪律是「层级靠结构不靠颜色」, 场景色一律不许进来。
   抬 2px 是结构层面的回应, 不动墨色, 所以不违反那条纪律。 */

.panel {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 16px;
  margin-bottom: 16px;
}
.panel h3 { margin: 0 0 16px 0; font-family: var(--font-display); font-size: 16px; color: var(--ink); }

.trend-chart {
  position: relative;
}
.trend-svg {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
}
.grid-line {
  stroke: var(--rule);
  stroke-width: 1;
}
.trend-area {
  fill: url(#trendFadeGrad);
}
.trend-line {
  fill: none;
  stroke: var(--accent);
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.trend-dot {
  fill: var(--accent);
}
.trend-tick {
  fill: var(--ink-3);
  font-family: var(--font-mono);
  font-size: 12px;
}
.trend-max {
  fill: var(--ink-3);
  font-family: var(--font-mono);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.trend-hit {
  fill: transparent;
  pointer-events: all;
  cursor: crosshair;
}

/* 按左上角对齐:上方态整体上移一格,下方态原地 —— 与 tooltipPlacement().below 配套 */
.trend-tip {
  position: absolute;
  z-index: 2;
  transform: translateY(-100%);
  display: flex;
  flex-direction: column;
  gap: 2px;
  white-space: nowrap;
  padding: 6px 8px;
  background: var(--paper-raised);
  border: 1px solid var(--rule-strong);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-tooltip);
  pointer-events: none;
}
.trend-tip.below {
  transform: translateY(0);
}
.tip-date {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-3);
}
.tip-amount {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  font-weight: 600;
  color: var(--accent);
}

.movie-bar { display: flex; flex-direction: column; gap: 12px; }
/* 排行行: 两行式 —— 第一行「名 + 营收」, 第二行是通栏的占比条。
   原来的 4 列一行式在窄栏(趋势图并排后只剩 4/12 宽)里会把条挤没。 */
.movie-row {
  display: grid;
  grid-template-columns: 28px 1fr auto;
  grid-template-areas:
    "rank name rev"
    "rank bar  bar";
  align-items: center;
  gap: 4px 12px;
  padding: 6px 8px;
  margin: 0 -8px;
  border-radius: var(--radius-sm);
  transition: background-color var(--transition-normal);
}
.movie-row:hover {
  background: var(--paper-sunk);
}
.rank { grid-area: rank; }
.movie-name { grid-area: name; }
.movie-bar-wrap { grid-area: bar; }
.movie-rev { grid-area: rev; }
.rank {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--paper-sunk); color: var(--ink-3);
  display: flex; align-items: center; justify-content: center;
  font-family: var(--font-mono);
  font-weight: 600;
}
.movie-row:nth-child(1) .rank { background: var(--accent); color: var(--ink-inverse); }
.movie-name { font-weight: 500; }
.movie-bar-wrap { height: 8px; background: var(--paper-sunk); border-radius: 4px; overflow: hidden; }
.movie-bar-fill {
  height: 100%;
  background: var(--accent);
  transition: width 0.4s;
}
.movie-rev {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  color: var(--ink-2);
}

.rate-bar { position: relative; height: 18px; background: var(--paper-sunk); border-radius: 4px; overflow: hidden; }
.rate-fill { height: 100%; background: var(--accent); }
.rate-text {
  position: absolute; right: 8px; top: 0; line-height: 18px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 12px; color: var(--ink);
}
.empty { padding: 40px 0; text-align: center; color: var(--ink-3); }

/* T5: 导出对话框 */
.export-form { display: flex; flex-direction: column; gap: 18px; }
.form-row { display: flex; flex-direction: column; gap: 8px; }
.form-row.preview { flex-direction: row; align-items: center; gap: 12px; }
.form-label { font-size: 13px; color: var(--ink-3); }
.preview-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--accent);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}
.form-error {
  color: var(--danger);
  font-size: 13px;
  padding: 8px 12px;
  /* 错误提示是 --danger 语义, 不该借印色(朱红)底 —— 用中性纸面 + 左侧红线标记 */
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  border-left: 2px solid var(--danger);
  border-radius: var(--radius-sm);
}

/* 窄屏: 面板回落竖排, 统计卡两列 */
@media (max-width: 1279px) {
  .dashboard > .panel-trend,
  .dashboard > .panel-rank {
    grid-column: span 12;
  }
}

@media (max-width: 768px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  /* 排行行已是三列 areas 布局, 窄屏只缩字号 —— 不要再改列数,
     否则 area 名与列定义对不上, 片名会被挤进 80px 的窄列。 */
  .movie-row { font-size: 12px; }
  /* tooltip 是 HTML 固定 px 宽,窄屏下缩小以缓解出血 */
  .trend-tip { padding: 4px 6px; }
  .tip-amount { font-size: 12px; }
}
</style>
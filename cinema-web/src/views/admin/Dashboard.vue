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

function maxTrend(): number {
  if (!data.value?.weeklyTrend?.length) return 0
  return Math.max(...data.value.weeklyTrend.map((p) => Number(p.amount)), 1)
}

function maxMovie(): number {
  if (!data.value?.topMovies?.length) return 0
  return Math.max(...data.value.topMovies.map((m) => Number(m.revenue)), 1)
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
        <div class="card gold">
          <div class="card-label">{{ t('admin.cardTodayRevenue') }}</div>
          <div class="card-value">¥{{ fmtAmount(data.todayRevenue) }}</div>
        </div>
        <div class="card">
          <div class="card-label">{{ t('admin.cardTodayOrders') }}</div>
          <div class="card-value">{{ data.todayOrders ?? 0 }}</div>
          <div class="card-sub">{{ t('admin.cardTodaySub', { paid: data.todayPaid ?? 0, seats: data.todayPendingSeats ?? 0 }) }}</div>
        </div>
        <div class="card">
          <div class="card-label">{{ t('admin.cardCancelled') }}</div>
          <div class="card-value">{{ data.todayCancelled ?? 0 }}</div>
        </div>
        <div class="card">
          <div class="card-label">{{ t('admin.cardRefunded') }}</div>
          <div class="card-value">{{ data.todayRefunded ?? 0 }}</div>
        </div>
      </div>

      <!-- 7 日票房趋势(柱状) -->
      <div class="panel">
        <h3>{{ t('admin.trendTitle') }}</h3>
        <div class="bar-chart">
          <div v-for="p in data.weeklyTrend" :key="p.date" class="bar-col">
            <div class="bar-value">¥{{ fmtAmount(p.amount) }}</div>
            <div class="bar" :style="{ height: (Number(p.amount) / maxTrend() * 180) + 'px' }"></div>
            <div class="bar-label">{{ (p.date || '').slice(5) }}</div>
          </div>
        </div>
      </div>

      <!-- TOP 5 影片 -->
      <div class="panel">
        <h3>{{ t('admin.topMoviesTitle') }}</h3>
        <div v-if="!data.topMovies.length" class="empty">{{ t('admin.emptyMovies') }}</div>
        <div v-else class="movie-bar">
          <div v-for="(m, i) in data.topMovies" :key="i" class="movie-row">
            <div class="rank">{{ i + 1 }}</div>
            <div class="movie-name">{{ m.title }}</div>
            <div class="movie-bar-wrap">
              <div class="movie-bar-fill" :style="{ width: (Number(m.revenue) / maxMovie() * 100) + '%' }"></div>
            </div>
            <div class="movie-rev">¥{{ fmtAmount(m.revenue) }} · {{ m.orders + ' ' + t('admin.ordersUnit') }}</div>
          </div>
        </div>
      </div>

      <!-- 上座率 TOP 10 场次 -->
      <div class="panel">
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

.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.card {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 16px;
  position: relative;
  overflow: hidden;
}
.card.gold {
  background: var(--accent-wash);
  border-color: var(--rule-strong);
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
.card.gold .card-value { color: var(--accent); }
.card-sub {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 8px;
}

.panel {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 16px;
  margin-bottom: 16px;
}
.panel h3 { margin: 0 0 16px 0; font-family: var(--font-display); font-size: 16px; color: var(--ink); }

.bar-chart {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  height: 200px;
  padding: 0 8px;
}
.bar-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  justify-content: flex-end;
}
.bar-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 11px;
  color: var(--ink-3);
  margin-bottom: 4px;
  white-space: nowrap;
}
.bar {
  width: 100%;
  background: var(--accent);
  border-radius: var(--radius-md) var(--radius-md) 0 0;
  min-height: 2px;
  transition: height 0.4s;
}
.bar-label {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 8px;
}

.movie-bar { display: flex; flex-direction: column; gap: 12px; }
.movie-row {
  display: grid;
  grid-template-columns: 32px 140px 1fr 180px;
  align-items: center;
  gap: 12px;
}
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
  color: var(--ink-3);
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

@media (max-width: 768px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .movie-row { grid-template-columns: 24px 80px 1fr 100px; font-size: 12px; }
}
</style>
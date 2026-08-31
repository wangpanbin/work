<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dashboardSummary, type DashboardSummary } from '../../api/admin'

const router = useRouter()
const data = ref<DashboardSummary | null>(null)
const loading = ref(false)

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
      <h2>📊 经营看板</h2>
      <el-button @click="router.push('/admin')" plain>返回管理首页</el-button>
    </div>

    <template v-if="data">
      <!-- 4 卡片 -->
      <div class="cards">
        <div class="card gold">
          <div class="card-label">今日票房</div>
          <div class="card-value">¥{{ fmtAmount(data.todayRevenue) }}</div>
        </div>
        <div class="card">
          <div class="card-label">今日订单</div>
          <div class="card-value">{{ data.todayOrders ?? 0 }}</div>
          <div class="card-sub">已支付 {{ data.todayPaid ?? 0 }} / 锁座 {{ data.todayPendingSeats ?? 0 }} 座</div>
        </div>
        <div class="card">
          <div class="card-label">超时关单</div>
          <div class="card-value">{{ data.todayCancelled ?? 0 }}</div>
        </div>
        <div class="card">
          <div class="card-label">今日退票</div>
          <div class="card-value">{{ data.todayRefunded ?? 0 }}</div>
        </div>
      </div>

      <!-- 7 日票房趋势(柱状) -->
      <div class="panel">
        <h3>7 日票房趋势</h3>
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
        <h3>本周票房 TOP 5 影片</h3>
        <div v-if="!data.topMovies.length" class="empty">本周暂无票房数据</div>
        <div v-else class="movie-bar">
          <div v-for="(m, i) in data.topMovies" :key="i" class="movie-row">
            <div class="rank">{{ i + 1 }}</div>
            <div class="movie-name">{{ m.title }}</div>
            <div class="movie-bar-wrap">
              <div class="movie-bar-fill" :style="{ width: (Number(m.revenue) / maxMovie() * 100) + '%' }"></div>
            </div>
            <div class="movie-rev">¥{{ fmtAmount(m.revenue) }} · {{ m.orders }} 单</div>
          </div>
        </div>
      </div>

      <!-- 上座率 TOP 10 场次 -->
      <div class="panel">
        <h3>场次上座率 TOP 10</h3>
        <el-table v-if="data.topSessions.length" :data="data.topSessions" stripe size="small">
          <el-table-column prop="movieTitle" label="影片" />
          <el-table-column prop="hallName" label="影厅" width="120" />
          <el-table-column prop="startTime" label="开场时间" width="170" />
          <el-table-column label="上座率" width="200">
            <template #default="{ row }">
              <div class="rate-bar">
                <div class="rate-fill" :style="{ width: (Number(row.occupancyRate) * 100) + '%' }"></div>
                <span class="rate-text">{{ fmtRate(row.occupancyRate) }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="empty">暂无场次数据</div>
      </div>
    </template>
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
  margin-bottom: 24px;
}
.page-header h2 { margin: 0; font-size: 22px; }

.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
.card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 24px;
  position: relative;
  overflow: hidden;
}
.card.gold {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.15), rgba(245, 158, 11, 0.04));
  border-color: rgba(245, 158, 11, 0.3);
}
.card-label {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 12px;
  letter-spacing: 1px;
}
.card-value {
  font-size: 32px;
  font-weight: 700;
  font-family: var(--font-display);
  color: var(--text-primary);
}
.card.gold .card-value { color: var(--accent-gold); }
.card-sub {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 8px;
}

.panel {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 24px;
  margin-bottom: 24px;
}
.panel h3 { margin: 0 0 20px 0; font-size: 16px; color: var(--text-primary); }

.bar-chart {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  height: 240px;
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
  font-size: 11px;
  color: var(--text-muted);
  margin-bottom: 4px;
  white-space: nowrap;
}
.bar {
  width: 100%;
  background: linear-gradient(180deg, var(--accent-gold), rgba(245, 158, 11, 0.3));
  border-radius: 4px 4px 0 0;
  min-height: 2px;
  transition: height 0.4s;
}
.bar-label {
  font-size: 12px;
  color: var(--text-muted);
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
  background: var(--bg-tertiary); color: var(--text-muted);
  display: flex; align-items: center; justify-content: center;
  font-weight: 600;
}
.movie-row:nth-child(1) .rank { background: var(--accent-gold); color: #000; }
.movie-name { font-weight: 500; }
.movie-bar-wrap { height: 8px; background: var(--bg-tertiary); border-radius: 4px; overflow: hidden; }
.movie-bar-fill {
  height: 100%;
  background: var(--gradient-gold);
  transition: width 0.4s;
}
.movie-rev { font-size: 13px; color: var(--text-muted); }

.rate-bar { position: relative; height: 18px; background: var(--bg-tertiary); border-radius: 4px; overflow: hidden; }
.rate-fill { height: 100%; background: var(--gradient-gold); }
.rate-text { position: absolute; right: 8px; top: 0; line-height: 18px; font-size: 12px; color: var(--text-primary); }
.empty { padding: 40px 0; text-align: center; color: var(--text-muted); }

@media (max-width: 768px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .movie-row { grid-template-columns: 24px 80px 1fr 100px; font-size: 12px; }
}
</style>

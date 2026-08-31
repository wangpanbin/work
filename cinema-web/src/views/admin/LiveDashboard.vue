<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { dashboardSummary, type DashboardSummary } from '../../api/admin'

const data = ref<DashboardSummary | null>(null)
const events = ref<Array<{ time: string; type: string; text: string }>>([])
const connected = ref(false)
let ws: WebSocket | null = null

function fmt(n: number | undefined) {
  if (n == null) return '0'
  return Number(n).toLocaleString()
}
function fmtMoney(n: number | undefined) {
  if (n == null) return '0.00'
  return Number(n).toFixed(2)
}

async function loadSummary() {
  try {
    data.value = await dashboardSummary()
  } catch (e) {
    // ignore
  }
}

function connectWs() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${location.host}/ws/admin`
  ws = new WebSocket(url)
  ws.onopen = () => {
    connected.value = true
    pushEvent('INFO', 'WebSocket 已连接')
  }
  ws.onclose = () => {
    connected.value = false
    pushEvent('WARN', 'WebSocket 断开,3s 后重连')
    setTimeout(connectWs, 3000)
  }
  ws.onerror = () => {
    pushEvent('ERR', 'WebSocket 错误')
  }
  ws.onmessage = (e) => {
    try {
      const env = JSON.parse(e.data)
      const t = env.type as string
      const d = env.data || {}
      let text = ''
      if (t === 'LOCK') text = `用户 ${d.userId} 锁座 session=${d.sessionId} 座位 ${(d.seats || []).join(',')}`
      else text = `${t}: ${JSON.stringify(d)}`
      pushEvent(t, text)
    } catch {
      // ignore
    }
  }
}

function pushEvent(type: string, text: string) {
  const time = new Date().toLocaleTimeString()
  events.value.unshift({ time, type, text })
  if (events.value.length > 50) events.value.length = 50
}

onMounted(() => {
  loadSummary()
  connectWs()
  // 每 5s 刷一次统计
  setInterval(loadSummary, 5000)
})

onUnmounted(() => {
  if (ws) ws.close()
})
</script>

<template>
  <div class="live">
    <div class="live-header">
      <h2>📡 实时数据大屏</h2>
      <div class="conn-status" :class="{ ok: connected, off: !connected }">
        <span class="dot"></span>
        {{ connected ? '已连接' : '未连接' }}
      </div>
    </div>

    <div v-if="data" class="cards">
      <div class="card gold">
        <div class="card-label">今日票房</div>
        <div class="card-value">¥{{ fmtMoney(data.todayRevenue) }}</div>
      </div>
      <div class="card">
        <div class="card-label">今日订单</div>
        <div class="card-value">{{ fmt(data.todayOrders) }}</div>
      </div>
      <div class="card">
        <div class="card-label">今日已支付</div>
        <div class="card-value">{{ fmt(data.todayPaid) }}</div>
      </div>
      <div class="card">
        <div class="card-label">今日锁座</div>
        <div class="card-value">{{ fmt(data.todayPendingSeats) }}</div>
      </div>
    </div>

    <div class="panel">
      <h3>实时事件流</h3>
      <div class="event-list">
        <div v-for="(e, i) in events" :key="i" class="event-row" :class="e.type">
          <span class="event-time">{{ e.time }}</span>
          <span class="event-type">{{ e.type }}</span>
          <span class="event-text">{{ e.text }}</span>
        </div>
        <div v-if="!events.length" class="empty">暂无事件,试试在另一个浏览器窗口下单吧~</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.live { animation: fadeInUp 0.5s ease; }
.live-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 24px;
}
.live-header h2 { margin: 0; font-size: 22px; }
.conn-status {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; padding: 4px 12px; border-radius: 12px;
}
.conn-status.ok { background: rgba(34, 197, 94, 0.15); color: #4ade80; }
.conn-status.off { background: rgba(239, 68, 68, 0.15); color: #f87171; }
.dot {
  width: 8px; height: 8px; border-radius: 50%; background: currentColor;
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.cards {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px;
}
.card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 24px;
}
.card.gold {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.15), rgba(245, 158, 11, 0.04));
  border-color: rgba(245, 158, 11, 0.3);
}
.card-label { font-size: 13px; color: var(--text-muted); margin-bottom: 12px; }
.card-value { font-size: 32px; font-weight: 700; color: var(--text-primary); }
.card.gold .card-value { color: var(--accent-gold); }

.panel {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 24px;
}
.panel h3 { margin: 0 0 16px 0; font-size: 16px; }
.event-list {
  max-height: 480px; overflow-y: auto;
  background: var(--bg-primary);
  border-radius: var(--radius-md);
  padding: 12px;
}
.event-row {
  display: grid;
  grid-template-columns: 90px 80px 1fr;
  gap: 12px;
  padding: 8px 12px;
  font-size: 13px;
  border-radius: 4px;
  margin-bottom: 4px;
  font-family: var(--font-mono, monospace);
}
.event-row.LOCK { background: rgba(59, 130, 246, 0.1); }
.event-row.SOLD { background: rgba(34, 197, 94, 0.1); }
.event-row.RELEASE { background: rgba(168, 85, 247, 0.1); }
.event-row.WARN { background: rgba(245, 158, 11, 0.1); }
.event-row.ERR { background: rgba(239, 68, 68, 0.1); }
.event-time { color: var(--text-muted); }
.event-type {
  font-weight: 600;
  color: var(--text-primary);
  background: var(--bg-tertiary);
  padding: 0 8px;
  border-radius: 4px;
  text-align: center;
}
.event-text { color: var(--text-secondary); }
.empty { padding: 40px 0; text-align: center; color: var(--text-muted); }

@media (max-width: 768px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .event-row { grid-template-columns: 70px 60px 1fr; font-size: 12px; }
}
</style>

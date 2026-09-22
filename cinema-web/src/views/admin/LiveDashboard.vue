<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dashboardSummary, type DashboardSummary } from '../../api/admin'

const { t, locale } = useI18n()
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
    pushEvent('INFO', t('admin.liveWsConnected'))
  }
  ws.onclose = () => {
    connected.value = false
    pushEvent('WARN', t('admin.liveWsClosed'))
    setTimeout(connectWs, 3000)
  }
  ws.onerror = () => {
    pushEvent('ERR', t('admin.liveWsError'))
  }
  ws.onmessage = (e) => {
    try {
      const env = JSON.parse(e.data)
      const t = env.type as string
      const d = env.data || {}
      const seats = (d.seats || []).join(',') || '-'
      const amt = d.amount != null ? ' ¥' + Number(d.amount).toFixed(2) : ''
      let text = ''
      // 事件 text 是"业务事件描述" + 数据 ID — 这些是技术性描述,保留中文够用
      // (完整多语言要后端配合发事件模板 + 语言 code,超出 i18n scope)
      if (t === 'LOCK') text = `用户 ${d.userId} 锁座 session=${d.sessionId} 座位 ${seats}`
      else if (t === 'SOLD') text = `用户 ${d.userId} 支付成功 session=${d.sessionId} 座位 ${seats}${amt}`
      else if (t === 'CANCEL') text = `用户 ${d.userId} 主动取消 session=${d.sessionId} 座位 ${seats}`
      else if (t === 'TIMEOUT') text = `订单 ${d.orderNo} 超时关单 session=${d.sessionId} 座位 ${seats}`
      else if (t === 'REFUND') text = `用户 ${d.userId} 退票成功 session=${d.sessionId} 座位 ${seats}${amt}`
      else text = `${t}: ${JSON.stringify(d)}`
      pushEvent(t, text)
    } catch {
      // ignore
    }
  }
}

function pushEvent(type: string, text: string) {
  // 时间格式跟随 i18n locale (spec §3.5)
  const time = new Date().toLocaleTimeString(locale.value)
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
      <h2>{{ t('admin.liveTitle') }}</h2>
      <div class="conn-status" :class="{ ok: connected, off: !connected }">
        <span class="dot"></span>
        {{ connected ? t('admin.liveConnected') : t('admin.liveDisconnected') }}
      </div>
    </div>

    <div v-if="data" class="cards">
      <div class="card gold">
        <div class="card-label">{{ t('admin.cardTodayRevenue') }}</div>
        <div class="card-value">¥{{ fmtMoney(data.todayRevenue) }}</div>
      </div>
      <div class="card">
        <div class="card-label">{{ t('admin.cardTodayOrders') }}</div>
        <div class="card-value">{{ fmt(data.todayOrders) }}</div>
      </div>
      <div class="card">
        <div class="card-label">{{ t('admin.cardTodayPaid') }}</div>
        <div class="card-value">{{ fmt(data.todayPaid) }}</div>
      </div>
      <div class="card">
        <div class="card-label">{{ t('admin.cardTodayLocked') }}</div>
        <div class="card-value">{{ fmt(data.todayPendingSeats) }}</div>
      </div>
    </div>

    <div class="panel">
      <h3>{{ t('admin.liveEventsTitle') }}</h3>
      <div class="event-list">
        <div v-for="(e, i) in events" :key="i" class="event-row" :class="e.type">
          <span class="event-time">{{ e.time }}</span>
          <span class="event-type">{{ e.type }}</span>
          <span class="event-text">{{ e.text }}</span>
        </div>
        <div v-if="!events.length" class="empty">{{ t('admin.liveEmptyHint') }}</div>
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
.event-row.LOCK    { background: rgba(59, 130, 246, 0.1); }
.event-row.SOLD   { background: rgba(34, 197, 94, 0.1); }
.event-row.CANCEL { background: rgba(168, 85, 247, 0.1); }
.event-row.TIMEOUT{ background: rgba(245, 158, 11, 0.1); }
.event-row.REFUND { background: rgba(236, 72, 153, 0.1); }
.event-row.WARN   { background: rgba(245, 158, 11, 0.1); }
.event-row.ERR    { background: rgba(239, 68, 68, 0.1); }
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

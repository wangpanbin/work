<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { dashboardSummary, type DashboardSummary } from '../../api/admin'

const { t, locale } = useI18n()
/** WS 消息处理里 `t` 被事件类型局部变量遮蔽, 这里留一个不冲突的别名给模板/事件拼装用 */
const t2 = t
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
      // 事件 text 完全在前端用 WS 载荷拼装(后端只发 userId/sessionId/seats/amount),
      // 所以不需要"后端配合发事件模板"就能 i18n —— 原注释的判断有误。
      // 载荷里的 ID / 座位号保持原样,只把描述词交给字典。
      if (t === 'LOCK') text = t2('admin.evLock', { user: d.userId, session: d.sessionId, seats })
      else if (t === 'SOLD') text = t2('admin.evSold', { user: d.userId, session: d.sessionId, seats, amt })
      else if (t === 'CANCEL') text = t2('admin.evCancel', { user: d.userId, session: d.sessionId, seats })
      else if (t === 'TIMEOUT') text = t2('admin.evTimeout', { order: d.orderNo, session: d.sessionId, seats })
      else if (t === 'REFUND') text = t2('admin.evRefund', { user: d.userId, session: d.sessionId, seats, amt })
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
  margin-bottom: 16px;
}
.live-header h2 { margin: 0; font-family: var(--font-display); font-size: 22px; }
.conn-status {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; padding: 4px 12px; border-radius: 12px;
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
}
.conn-status.ok { color: var(--ok); }
.conn-status.off { color: var(--danger); border-color: var(--danger); }
.dot {
  width: 8px; height: 8px; border-radius: 50%; background: currentColor;
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.cards {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px;
}
.card {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 16px;
}
.card.gold {
  background: var(--accent-wash);
  border-color: var(--rule-strong);
}
.card-label { font-size: 13px; color: var(--ink-3); margin-bottom: 8px; }
/* 实时数值: 等宽 + tabular-nums, 5s 轮询刷新时数字不左右跳 */
.card-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 30px; font-weight: 700; color: var(--ink);
}
.card.gold .card-value { color: var(--accent); }

.panel {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 16px;
}
.panel h3 { margin: 0 0 12px 0; font-family: var(--font-display); font-size: 16px; }
.event-list {
  max-height: 480px; overflow-y: auto;
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  border-radius: var(--radius-md);
  padding: 8px;
}
.event-row {
  display: grid;
  grid-template-columns: 90px 80px 1fr;
  gap: 12px;
  padding: 8px 12px;
  font-size: 13px;
  background: var(--paper-raised);
  border-left: 2px solid var(--rule);
  border-radius: var(--radius-sm);
  margin-bottom: 4px;
  font-family: var(--font-mono);
}
/* 事件语义色: 印刷风用左侧 2px 印色条区分, 不用半透明底色 */
.event-row.LOCK    { border-left-color: var(--ink-3); }
.event-row.SOLD    { border-left-color: var(--ok); }
.event-row.CANCEL  { border-left-color: var(--ink-2); }
.event-row.TIMEOUT { border-left-color: var(--warn); }
.event-row.REFUND  { border-left-color: var(--accent); }
.event-row.WARN    { border-left-color: var(--warn); }
.event-row.ERR     { border-left-color: var(--danger); }
.event-time { color: var(--ink-3); }
.event-type {
  font-weight: 600;
  color: var(--ink);
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  padding: 0 8px;
  border-radius: var(--radius-sm);
  text-align: center;
}
.event-text { color: var(--ink-2); }
.empty { padding: 40px 0; text-align: center; color: var(--ink-3); }

@media (max-width: 768px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .event-row { grid-template-columns: 70px 60px 1fr; font-size: 12px; }
}
</style>

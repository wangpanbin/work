<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { seatMap } from '../api/seat'
import { lockSeats } from '../api/order'
import { useUserStore } from '../stores/user'
import { useSeatStore } from '../stores/seat'
import { createSeatWs, type SeatWsHandle, type WsStatus } from '../utils/ws'
import SeatItem from '../components/SeatItem.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const seatStore = useSeatStore()

// 后端 sessionId 是雪花 ID (19 位, 超过 JS Number.MAX_SAFE_INTEGER=2^53-1=9007199254740991),
// 必须保持 string 才能避免精度丢失 —— 否则 request URL 里最后几位会变 0, 后端查不到
const sessionId = computed(() => String(route.params.sessionId))
const submitting = ref(false)
const refreshing = ref(false)
let wsHandle: SeatWsHandle | null = null

// P0-2: WS 连接状态(从 wsHandle.status 同步过来给 template 用)
const wsStatus = ref<WsStatus>('connecting')

async function refresh() {
  refreshing.value = true
  try {
    const m = await seatMap(sessionId.value)
    seatStore.load(m)
  } finally {
    refreshing.value = false
  }
}

onMounted(async () => {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  await refresh()
  wsHandle = createSeatWs(sessionId.value, (evt) => {
    const before = seatStore.selected.size
    seatStore.applyEvent(evt.type, evt.seats)
    // P0-2: WS 事件导致 selected 减少时, 提示用户具体被抢了哪个
    if ((evt.type === 'LOCKED' || evt.type === 'SOLD') && seatStore.selected.size < before) {
      const lostCount = before - seatStore.selected.size
      ElMessage.warning(`你已选的 ${lostCount} 个座位被他人锁定,已自动移出选择`)
    }
  })
  // 同步初始状态, 后续 watch 自动更新
  wsStatus.value = wsHandle.status.value
  watch(wsHandle.status, (s) => { wsStatus.value = s })
})

onBeforeUnmount(() => {
  wsHandle?.close()
})

const remainingSeconds = computed(() => {
  if (!seatStore.map) return 0
  const left = dayjs(seatStore.map.startTime).diff(dayjs(), 'second')
  return left > 0 ? left : 0
})

const totalPrice = computed(() => {
  if (!seatStore.map) return 0
  return (seatStore.selected.size * seatStore.map.price).toFixed(2)
})

async function onConfirm() {
  const seats = [...seatStore.selected]
  if (seats.length === 0) {
    ElMessage.warning('请先选择座位')
    return
  }
  submitting.value = true
  try {
    const result = await lockSeats({ sessionId: sessionId.value, seatIndexes: seats.sort((a, b) => a - b) })
    ElMessage.success('锁座成功!')
    seatStore.markMyLocked(seats)
    router.push({ name: 'payment', query: { orderNo: result.orderNo } })
  } catch (e: unknown) {
    const err = e as { message?: string; data?: { conflict?: number[] } }
    const msg = err?.message || '锁座失败'
    if (msg.includes('座位已被占用')) {
      // Phase D-⑱: 只对后端返回的 conflict 列表做局部置灰, 避免全量 refresh
      const conflict = err?.data?.conflict
      if (Array.isArray(conflict) && conflict.length > 0) {
        seatStore.applyEvent('LOCKED', conflict)
        // P1-#6: 把被抢的座位转成"X排Y座"格式, 让用户一眼能定位
        const labels = conflict.map((idx) => {
          const { row, col } = seatStore.rowCol(idx)
          return `${row}排${col}座`
        })
        const shown = labels.length > 4 ? `${labels.slice(0, 4).join('、')} 等 ${labels.length} 个` : labels.join('、')
        ElMessage.error(`所选座位已被抢走: ${shown}, 请重新选择`)
      } else {
        // 兜底: 拿不到 conflict 列表时全量 refresh
        await refresh()
        ElMessage.error('所选座位已被抢走,已刷新座位图')
      }
    } else {
      ElMessage.error(msg)
    }
  } finally {
    submitting.value = false
  }
}

function seatClick(idx: number) {
  if (!seatStore.toggle(idx)) {
    const status = seatStore.statusAt(idx)
    if (status === 'SOLD' || status === 'LOCKED_OTHER') {
      ElMessage.info('该座位不可选')
    } else if (seatStore.selected.size >= seatStore.maxSelect) {
      ElMessage.warning(`最多选择 ${seatStore.maxSelect} 个座位`)
    }
  }
}
</script>

<template>
  <div class="seat-select" v-if="seatStore.map">
    <!-- P0-2: WS 连接状态条 — 断连/重连时给用户明确反馈, 避免静默失同步 -->
    <div class="ws-status" :class="`ws-${wsStatus}`" role="status" aria-live="polite">
      <span class="ws-dot"></span>
      <span class="ws-text">
        <template v-if="wsStatus === 'open'">实时同步中</template>
        <template v-else-if="wsStatus === 'connecting'">正在连接实时同步…</template>
        <template v-else>已断开,正在重连 — 座位状态可能未及时更新</template>
      </span>
    </div>

    <!-- Header -->
    <div class="header-card">
      <div class="header-info">
        <h2 class="title">{{ seatStore.map.movieTitle }}</h2>
        <div class="meta">
          <span class="meta-item">{{ seatStore.map.hallName }}</span>
          <span class="meta-sep">·</span>
          <span class="meta-item">{{ dayjs(seatStore.map.startTime).format('MM-DD HH:mm') }}</span>
          <span class="meta-sep">·</span>
          <span class="meta-item price-tag">￥{{ seatStore.map.price.toFixed(2) }}/座</span>
        </div>
      </div>
      <el-button @click="refresh" :loading="refreshing" class="refresh-btn">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" style="margin-right:4px">
          <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
        </svg>
        刷新
      </el-button>
    </div>

    <!-- Screen -->
    <div class="screen-wrapper">
      <div class="screen">
        <div class="screen-inner">银 幕</div>
        <div class="screen-reflection"></div>
      </div>
      <div class="screen-stand"></div>
    </div>

    <!-- Seats Grid (Phase D-⑬: 抽 SeatItem 子组件, 父级只传 :index)
         行/列标签: 左侧行号 + 顶部列号, 统一挂在 seats-container 上设 --cols/--rows -->
    <div class="seats-container" :style="{ '--cols': seatStore.map.cols, '--rows': seatStore.map.rows }">
      <div class="seats-grid" v-if="seatStore.map.cols <= 16">
        <!-- 列号表头: 占 1 格给左侧行号标签 -->
        <div class="corner-spacer"></div>
        <div v-for="c in seatStore.map.cols" :key="`c-${c}`" class="col-label">{{ c }}</div>
        <!-- 每一行: 行号 + cols 个座位 -->
        <template v-for="r in seatStore.map.rows" :key="`r-${r}`">
          <div class="row-label">{{ r }}</div>
          <template v-for="(_, idx) in seatStore.map.cols" :key="`c-${r}-${idx}`">
            <SeatItem :index="(r - 1) * seatStore.map.cols + idx" />
          </template>
        </template>
      </div>
      <!-- 超过 16 列: 退化为横向单行, 不显示行列标签 (大影厅) -->
      <div v-else>
        <!-- P2-#13: 大影厅布局丢了行列标签, 加个 hint 给用户交代 -->
        <div class="seats-hint">
          💡 本场 {{ seatStore.map.rows }} 排 × {{ seatStore.map.cols }} 座 · 长按 / 悬停座位可看具体位置
        </div>
        <div class="seats" :style="{ '--cols': seatStore.map.cols }">
          <SeatItem v-for="i in seatStore.map.seatCount" :key="i - 1" :index="i - 1" />
        </div>
      </div>
    </div>

    <!-- Legend -->
    <div class="legend">
      <div class="item"><span class="dot available" />可选</div>
      <div class="item"><span class="dot selected" />已选</div>
      <div class="item"><span class="dot mine" />我已锁</div>
      <div class="item"><span class="dot locked" />他人锁定</div>
      <div class="item"><span class="dot sold" />已售</div>
    </div>

    <!-- Summary Card -->
    <div class="summary">
      <div class="summary-item">
        <div class="summary-icon">🎯</div>
        <div class="summary-content">
          <div class="summary-value">{{ seatStore.selected.size }} / {{ seatStore.maxSelect }}</div>
          <div class="summary-label">已选座位</div>
        </div>
      </div>
      <div class="summary-divider"></div>
      <div class="summary-item">
        <div class="summary-icon">⏰</div>
        <div class="summary-content">
          <div class="summary-value">{{ Math.floor(remainingSeconds / 3600) }}h {{ Math.floor((remainingSeconds % 3600) / 60) }}m</div>
          <div class="summary-label">开映倒计时</div>
        </div>
      </div>
      <div class="summary-divider"></div>
      <div class="summary-item highlight">
        <div class="summary-icon">💰</div>
        <div class="summary-content">
          <div class="summary-value price">￥{{ totalPrice }}</div>
          <div class="summary-label">合计金额</div>
        </div>
      </div>
      <el-button type="primary" size="large" :loading="submitting" @click="onConfirm" class="confirm-btn">
        确认锁座下单
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.seat-select {
  display: flex;
  flex-direction: column;
  gap: 20px;
  animation: fadeInUp 0.5s ease;
}

/* --- Header --- */
.header-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 28px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
}

.title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 8px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-item {
  color: var(--text-muted);
  font-size: 13px;
}

.meta-sep {
  color: var(--border-color);
}

.price-tag {
  color: var(--accent-gold);
  font-weight: 600;
}

.refresh-btn {
  flex-shrink: 0;
}

/* --- Screen --- */
.screen-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin: 8px 0 4px;
}

.screen {
  position: relative;
  width: 70%;
  min-width: 280px;
  height: 36px;
  border-radius: 50% 50% 0 0 / 100% 100% 0 0;
  background: linear-gradient(180deg, var(--accent-gold) 0%, rgba(245, 158, 11, 0.3) 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 40px rgba(245, 158, 11, 0.2);
}

.screen-inner {
  font-family: var(--font-display);
  font-size: 14px;
  letter-spacing: 8px;
  color: var(--bg-primary);
  font-weight: 700;
}

.screen-reflection {
  position: absolute;
  bottom: -20px;
  left: 50%;
  transform: translateX(-50%);
  width: 60%;
  height: 20px;
  background: linear-gradient(180deg, rgba(245, 158, 11, 0.15), transparent);
  border-radius: 50%;
  filter: blur(8px);
}

.screen-stand {
  width: 4px;
  height: 16px;
  background: var(--border-color);
  margin-top: 4px;
}

/* --- Seats Container --- */
.seats-container {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  padding: 24px;
  overflow-x: auto;
}

/* 带行列标签的网格 (cols <= 16) */
.seats-grid {
  display: grid;
  grid-template-columns: 32px repeat(var(--cols), minmax(28px, 1fr));
  gap: 6px;
  min-width: max-content;
}
.corner-spacer {
  /* 左上角空白: 与列号行平齐 */
}
.col-label {
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 500;
  user-select: none;
  height: 16px;
  line-height: 16px;
}
.row-label {
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
  font-weight: 500;
  user-select: none;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 行高与座位高一致 (座位 aspect-ratio: 1 + 字号 + padding 估算 ~38-44px) */
  height: 38px;
}

/* 大影厅退化为单行网格 */
.seats {
  display: grid;
  grid-template-columns: repeat(var(--cols), 1fr);
  gap: 6px;
}

/* P2-#13: 大影厅 hint */
.seats-hint {
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 12px;
  padding: 6px 12px;
  background: rgba(245, 158, 11, 0.06);
  border-radius: var(--radius-sm);
  border: 1px dashed rgba(245, 158, 11, 0.2);
}

.seat {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  background: var(--bg-tertiary);
  border: 1px solid var(--border-color);
  border-radius: 4px 4px 8px 8px;
  cursor: pointer;
  user-select: none;
  transition: all var(--transition-fast);
  color: var(--text-muted);
  position: relative;
  min-width: 28px;
  min-height: 38px;
  /* P2-#14: 触摸优化 — 移动端 44x44 是 Apple HIG 推荐的最小点击区 */
  touch-action: manipulation;
}

.seat:hover:not(.locked_other):not(.sold) {
  transform: translateY(-2px);
  border-color: var(--accent-gold);
  color: var(--text-primary);
}

.seat.available {
  background: var(--bg-tertiary);
  color: var(--text-muted);
}

.seat.selected {
  background: var(--gradient-gold);
  border-color: var(--accent-gold);
  color: var(--text-inverse);
  box-shadow: 0 0 12px rgba(245, 158, 11, 0.5);
}

.seat.mine {
  background: linear-gradient(135deg, #10b981, #059669);
  border-color: #10b981;
  color: #fff;
}

.seat.locked_other {
  background: var(--bg-elevated);
  color: var(--text-muted);
  border-color: var(--border-color);
  cursor: not-allowed;
  opacity: 0.5;
}

.seat.sold {
  background: #374151;
  color: #4b5563;
  cursor: not-allowed;
  border-color: #4b5563;
}

/* --- Legend --- */
.legend {
  display: flex;
  justify-content: center;
  gap: 20px;
  flex-wrap: wrap;
  padding: 8px 0;
}

.legend .item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.legend .dot {
  width: 18px;
  height: 18px;
  border-radius: 4px 4px 6px 6px;
  border: 1px solid var(--border-color);
}

.legend .dot.available { background: var(--bg-tertiary); }
.legend .dot.selected { background: var(--gradient-gold); border-color: var(--accent-gold); }
.legend .dot.mine { background: linear-gradient(135deg, #10b981, #059669); border-color: #10b981; }
.legend .dot.locked { background: var(--bg-elevated); opacity: 0.5; }
.legend .dot.sold { background: #374151; border-color: #4b5563; }

/* --- Summary Card --- */
.summary {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 20px 28px;
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  flex-wrap: wrap;
}

.summary-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.summary-item.highlight {
  flex: 1;
}

.summary-icon {
  font-size: 24px;
}

.summary-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.summary-label {
  font-size: 12px;
  color: var(--text-muted);
}

.summary-value.price {
  font-family: var(--font-display);
  font-size: 28px;
  color: var(--accent-red);
}

.summary-divider {
  width: 1px;
  height: 32px;
  background: var(--border-color);
}

.confirm-btn {
  flex-shrink: 0;
}

/* --- Responsive --- */
@media (max-width: 768px) {
  .header-card {
    padding: 16px 20px;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .seats-container {
    padding: 16px;
  }
  /* P2-#14: 移动端座位字号从 10px → 13px, 配合更大的最小尺寸, 触摸更准 */
  .seat {
    font-size: 13px;
    min-width: 32px;
    min-height: 32px;
  }
  .col-label,
  .row-label {
    font-size: 12px;
  }
  .summary {
    padding: 16px 20px;
    gap: 16px;
  }
  .summary-divider {
    display: none;
  }
  .summary-item {
    flex: 1;
  }
  .confirm-btn {
    width: 100%;
  }
}

@media (max-width: 480px) {
  .summary-item {
    flex: 0 0 100%;
    justify-content: flex-start;
  }
  .summary-value.price {
    font-size: 22px;
  }
}

/* --- P0-2: WS 连接状态条 --- */
.ws-status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 500;
  border: 1px solid;
  animation: fadeInUp 0.3s ease;
}
.ws-status .ws-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.ws-status.ws-open {
  background: rgba(16, 185, 129, 0.08);
  border-color: rgba(16, 185, 129, 0.3);
  color: #10b981;
}
.ws-status.ws-open .ws-dot {
  background: #10b981;
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.6);
  animation: ws-pulse 2s ease-in-out infinite;
}
.ws-status.ws-connecting {
  background: rgba(245, 158, 11, 0.08);
  border-color: rgba(245, 158, 11, 0.3);
  color: var(--accent-gold-light);
}
.ws-status.ws-connecting .ws-dot {
  background: var(--accent-gold);
  animation: ws-blink 1s ease-in-out infinite;
}
.ws-status.ws-closed {
  background: rgba(239, 68, 68, 0.08);
  border-color: rgba(239, 68, 68, 0.3);
  color: #fca5a5;
}
.ws-status.ws-closed .ws-dot {
  background: #ef4444;
  animation: ws-blink 0.6s ease-in-out infinite;
}
@keyframes ws-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.6); }
  50% { box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }
}
@keyframes ws-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* --- P0-2: 被他人抢走的已选座位 — 1.8s 红色脉冲外环, 强提示 --- */
.seat.flash {
  animation: seat-conflict 0.4s ease-in-out 4;
  position: relative;
  z-index: 2;
}
.seat.flash::before {
  content: '';
  position: absolute;
  inset: -4px;
  border: 2px solid #ef4444;
  border-radius: 6px 6px 10px 10px;
  pointer-events: none;
  animation: seat-conflict-ring 0.4s ease-in-out 4;
}
@keyframes seat-conflict {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.18); }
}
@keyframes seat-conflict-ring {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.6); }
  50% { opacity: 0.5; box-shadow: 0 0 0 6px rgba(239, 68, 68, 0); }
}
</style>
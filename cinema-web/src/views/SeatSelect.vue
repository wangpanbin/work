<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { seatMap } from '../api/seat'
import { lockSeats } from '../api/order'
import { useUserStore } from '../stores/user'
import { useSeatStore } from '../stores/seat'
import { createSeatWs, type SeatWsHandle } from '../utils/ws'
import SeatItem from '../components/SeatItem.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const seatStore = useSeatStore()

const sessionId = computed(() => Number(route.params.sessionId))
const submitting = ref(false)
let wsHandle: SeatWsHandle | null = null

async function refresh() {
  const m = await seatMap(sessionId.value)
  seatStore.load(m)
}

onMounted(async () => {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  await refresh()
  wsHandle = createSeatWs(sessionId.value, (evt) => {
    seatStore.applyEvent(evt.type, evt.seats)
  })
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
        // 提示用户被抢的座位
        ElMessage.error(`所选座位已被抢走 (${conflict.length} 个),请重新选择`)
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
      <el-button @click="refresh" class="refresh-btn">
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

    <!-- Seats Grid (Phase D-⑬: 抽 SeatItem 子组件, 父级只传 :index) -->
    <div class="seats-container">
      <div class="row-labels" v-if="seatStore.map.cols <= 16">
        <span v-for="c in seatStore.map.cols" :key="c" class="col-label">{{ c }}</span>
      </div>
      <div class="seats" :style="{ '--cols': seatStore.map.cols }">
        <SeatItem
          v-for="i in seatStore.map.seatCount"
          :key="i - 1"
          :index="i - 1"
        />
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
}

.row-labels {
  display: grid;
  grid-template-columns: repeat(var(--cols), 1fr);
  gap: 6px;
  margin-bottom: 8px;
}

.col-label {
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
}

.seats {
  display: grid;
  grid-template-columns: repeat(var(--cols), 1fr);
  gap: 6px;
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
  .seat {
    font-size: 10px;
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
</style>
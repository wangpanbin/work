<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { orderDetail, pay, cancel } from '../api/order'
import type { OrderVO } from '../api/order'
import Countdown from '../components/Countdown.vue'

const route = useRoute()
const router = useRouter()
const orderNo = String(route.query.orderNo || '')
const order = ref<OrderVO | null>(null)
const submitting = ref(false)

onMounted(async () => {
  if (!orderNo) {
    ElMessage.error('订单号缺失')
    router.push('/')
    return
  }
  await load()
})

async function load() {
  order.value = await orderDetail(orderNo)
}

const remaining = computed(() => {
  if (!order.value || order.value.status !== 0) return 0
  return Math.max(0, dayjs(order.value.expireAt).diff(dayjs(), 'second'))
})

const expired = computed(() => remaining.value === 0 && order.value?.status === 0)

async function onPay() {
  submitting.value = true
  try {
    await pay(orderNo)
    ElMessage.success('支付成功!')
    await load()
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '支付失败')
  } finally {
    submitting.value = false
  }
}

async function onCancel() {
  submitting.value = true
  try {
    await cancel(orderNo)
    ElMessage.success('订单已取消')
    await load()
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '取消失败')
  } finally {
    submitting.value = false
  }
}

function fmt(t: number) {
  const m = Math.floor(t / 60)
  const s = t % 60
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

function goSeat() {
  if (!order.value) return
  router.push(`/seat/${order.value.sessionId}`)
}
</script>

<template>
  <div v-if="order" class="payment">
    <div class="payment-card">
      <!-- Header -->
      <div class="payment-header">
        <div class="header-icon">🎫</div>
        <div class="header-info">
          <h1 class="title">{{ order.movieTitle }}</h1>
          <div class="meta">{{ order.hallName }} · {{ dayjs(order.startTime).format('YYYY-MM-DD HH:mm') }}</div>
        </div>
        <div class="header-status">
          <el-tag :type="order.status === 1 ? 'success' : order.status === 2 ? 'info' : 'warning'" size="large" effect="dark">
            {{ order.statusText }}
          </el-tag>
        </div>
      </div>

      <!-- Seat Info -->
      <div class="seat-section">
        <div class="seat-desc">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="20" height="20">
            <rect x="3" y="3" width="18" height="18" rx="2"/>
            <path d="M3 9h18"/><path d="M9 21V9"/>
          </svg>
          {{ order.seatDesc }}
        </div>
      </div>

      <div class="divider"></div>

      <!-- Order Details -->
      <div class="details">
        <div class="info-row">
          <span class="label">订单号</span>
          <span class="value mono">{{ order.orderNo }}</span>
        </div>
        <div class="info-row">
          <span class="label">数量</span>
          <span class="value">{{ order.seatCount }} 座</span>
        </div>
        <div v-if="order.status === 0" class="info-row">
          <span class="label">支付倒计时</span>
          <span class="value" :class="{ urgent: remaining <= 60 }">
            <svg v-if="remaining <= 60" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16" style="margin-right:4px">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            <!-- Phase D-⑰: 倒计时用 Countdown 子组件, 父组件不再每秒重渲 -->
            <Countdown :expire-at="order.expireAt" :urgent-threshold="60" />
          </span>
        </div>
      </div>

      <div class="divider"></div>

      <!-- Total -->
      <div class="total-row">
        <span class="total-label">应付金额</span>
        <span class="total-value">
          <span class="currency">￥</span>
          <span class="amount">{{ order.totalAmount.toFixed(2) }}</span>
        </span>
      </div>

      <div class="divider"></div>

      <!-- Actions -->
      <div class="actions" v-if="order.status === 0">
        <el-button type="danger" plain @click="onCancel" :disabled="submitting" class="cancel-btn">取消订单</el-button>
        <el-button type="primary" size="large" @click="onPay" :loading="submitting" :disabled="expired" class="pay-btn">
          {{ expired ? '已超时,请重选座位' : '确认支付' }}
        </el-button>
      </div>
      <div v-else-if="order.status === 2" class="actions">
        <el-button type="primary" @click="goSeat">重新选座</el-button>
      </div>
      <div v-else class="actions success">
        <div class="success-content">
          <div class="success-icon">✓</div>
          <span>支付成功,祝您观影愉快!</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.payment {
  max-width: 600px;
  margin: 0 auto;
  animation: fadeInUp 0.5s ease;
}

.payment-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-xl);
  padding: 32px;
  box-shadow: var(--shadow-md);
}

.payment-header {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-icon {
  font-size: 40px;
  filter: drop-shadow(0 0 12px rgba(245, 158, 11, 0.3));
}

.header-info {
  flex: 1;
  min-width: 0;
}

.title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.meta {
  color: var(--text-muted);
  font-size: 13px;
}

/* --- Seat Section --- */
.seat-section {
  margin-top: 24px;
}

.seat-desc {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  background: rgba(245, 158, 11, 0.08);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: var(--radius-md);
  color: var(--accent-gold-light);
  font-size: 16px;
  font-weight: 600;
}

.divider {
  height: 1px;
  background: var(--border-subtle);
  margin: 24px 0;
}

/* --- Details --- */
.details {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.label {
  color: var(--text-muted);
  font-size: 14px;
}

.value {
  color: var(--text-primary);
  font-weight: 500;
}

.value.mono {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
}

.value.urgent {
  color: var(--accent-red);
  font-weight: 700;
  animation: pulse-glow 1.5s ease-in-out infinite;
}

/* --- Total --- */
.total-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.total-label {
  color: var(--text-secondary);
  font-size: 14px;
}

.total-value {
  display: flex;
  align-items: baseline;
}

.currency {
  color: var(--accent-red);
  font-size: 20px;
  font-weight: 600;
}

.amount {
  font-family: var(--font-display);
  font-size: 36px;
  font-weight: 700;
  color: var(--accent-red);
  line-height: 1;
}

/* --- Actions --- */
.actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.actions.success {
  justify-content: center;
}

.success-content {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--accent-gold-light);
  font-size: 16px;
  font-weight: 600;
}

.success-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--gradient-gold);
  color: var(--text-inverse);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 18px;
}

@media (max-width: 480px) {
  .payment-card {
    padding: 20px;
  }
  .payment-header {
    flex-direction: column;
    text-align: center;
  }
  .actions {
    flex-direction: column;
  }
  .actions .el-button {
    width: 100%;
  }
  .amount {
    font-size: 28px;
  }
}
</style>
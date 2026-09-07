<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import QRCode from 'qrcode'
import { orderDetail, pay, cancel, refund, getTicket } from '../api/order'
import type { OrderVO, TicketVO } from '../api/order'
import Countdown from '../components/Countdown.vue'

const route = useRoute()
const router = useRouter()
const orderNo = String(route.query.orderNo || '')
const order = ref<OrderVO | null>(null)
const submitting = ref(false)
const ticketDialog = ref(false)
const ticketInfo = ref<TicketVO | null>(null)
const ticketLoading = ref(false)
const ticketQrUrl = ref<string>('')
// P0-4: 超时自动跳转的"已触发"标记, 防止 watch + 用户点击双重触发
const autoRedirected = ref(false)

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

// P0-4: 待支付订单超时后, 不再让用户卡在支付页 — 3s 后自动跳订单列表
// 用 setTimeout 而非 immediate, 给用户一个看到"已超时"状态的窗口期
function redirectToOrders() {
  if (autoRedirected.value) return
  autoRedirected.value = true
  ElMessage.warning('订单已超时,即将跳转到订单列表,请重新选座')
  setTimeout(() => router.push('/orders'), 3000)
}

watch(expired, (isExpired) => {
  if (isExpired) redirectToOrders()
})

/** 距离场次开始还剩多久（用于退票按钮的可见性） */
const sessionNotStarted = computed(() => {
  if (!order.value?.startTime) return false
  return dayjs(order.value.startTime).isAfter(dayjs())
})

/** 状态机文案：覆盖所有 5 个状态, 避免 REFUNDED 还显示"支付成功"的尴尬 */
const statusBlock = computed(() => {
  const s = order.value?.status
  if (s === 0) return { kind: 'pending', text: '' }
  if (s === 1) return { kind: 'paid', text: '支付成功,祝您观影愉快!' }
  if (s === 2) return { kind: 'cancelled', text: '订单已取消' }
  if (s === 3) return { kind: 'refunding', text: '退款处理中,请稍候…' }
  if (s === 4) return { kind: 'refunded', text: '已退款,座位已释放' }
  return { kind: 'unknown', text: '' }
})

async function onPay() {
  submitting.value = true
  try {
    await pay(orderNo)
    ElMessage.success('支付成功!')
    await load()
  } catch {
    // 拦截器已弹错误, 不再重复
  } finally {
    submitting.value = false
  }
}

async function onCancel() {
  try {
    await ElMessageBox.confirm('确定要取消该订单吗?取消后座位将释放。', '提示', {
      confirmButtonText: '确定取消',
      cancelButtonText: '再想想',
      type: 'warning',
    })
  } catch {
    return
  }
  submitting.value = true
  try {
    await cancel(orderNo)
    ElMessage.success('订单已取消')
    await load()
  } catch {
    // 拦截器已弹错误
  } finally {
    submitting.value = false
  }
}

async function onRefund() {
  if (!sessionNotStarted.value) {
    ElMessage.warning('场次已开场,无法退票')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确定申请退票吗?退款将原路返回, 座位会立即释放, 此操作不可撤销。',
      '申请退票',
      { confirmButtonText: '确认退票', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    await refund(orderNo)
    ElMessage.success('退款申请已提交')
    await load()
  } catch {
    // 拦截器已弹错误
  } finally {
    submitting.value = false
  }
}

async function onShowTicket() {
  ticketDialog.value = true
  if (ticketInfo.value && ticketInfo.value.orderNo === orderNo) {
    return
  }
  ticketLoading.value = true
  try {
    ticketInfo.value = await getTicket(orderNo)
    // P0-5: 用 qrcode 库渲染真 QR(替代之前装饰用的伪二维码格子)
    // 内容 = 后端验证 URL, 影院扫码枪/手机扫码可直接验票
    const verifyBase = `${window.location.origin}/api/tickets/verify`
    const url = `${verifyBase}?payload=${encodeURIComponent(ticketInfo.value.payload)}&sig=${encodeURIComponent(ticketInfo.value.sig)}`
    ticketQrUrl.value = await QRCode.toDataURL(url, {
      errorCorrectionLevel: 'M',
      margin: 2,
      width: 360,
      color: { dark: '#111827', light: '#ffffff' },
    })
  } catch {
    ticketDialog.value = false
    ElMessage.error('生成二维码失败,请稍后重试')
  } finally {
    ticketLoading.value = false
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
          <el-tag
            :type="order.status === 1 ? 'success' : order.status === 2 || order.status === 4 ? 'info' : order.status === 3 ? 'warning' : 'warning'"
            size="large" effect="dark"
          >
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
            <Countdown :expire-at="order.expireAt" :urgent-threshold="60" />
          </span>
        </div>
      </div>

      <div class="divider"></div>

      <!-- Total -->
      <div class="total-row">
        <span class="total-label">{{ order.status === 4 ? '已退金额' : '应付金额' }}</span>
        <span class="total-value">
          <span class="currency">￥</span>
          <span class="amount">{{ order.totalAmount.toFixed(2) }}</span>
        </span>
      </div>

      <div class="divider"></div>

      <!-- Actions: 按状态分支 -->
      <!-- 待支付: 取消 + 支付 -->
      <div class="actions" v-if="order.status === 0">
        <el-button type="danger" plain @click="onCancel" :disabled="submitting" class="cancel-btn">取消订单</el-button>
        <el-button
          type="primary" size="large" @click="expired ? redirectToOrders() : onPay()"
          :loading="submitting" class="pay-btn"
        >
          {{ expired ? '已超时,重新选座' : '确认支付' }}
        </el-button>
      </div>
      <!-- 已取消: 重新选座 -->
      <div v-else-if="order.status === 2" class="actions">
        <el-button type="primary" @click="goSeat">重新选座</el-button>
      </div>
      <!-- 退款中: 进度条 + 等待 -->
      <div v-else-if="order.status === 3" class="actions">
        <div class="status-banner status-refunding">
          <div class="spinner"></div>
          <span>{{ statusBlock.text }}</span>
        </div>
      </div>
      <!-- 已退款: 重新选座 -->
      <div v-else-if="order.status === 4" class="actions">
        <div class="status-banner status-refunded">
          <div class="banner-icon">↩</div>
          <span>{{ statusBlock.text }}</span>
        </div>
        <el-button type="primary" @click="goSeat">重新选座</el-button>
      </div>
      <!-- 已支付: 取票 + 退票(开场前) -->
      <div v-else class="actions success">
        <div class="success-content">
          <div class="success-icon">✓</div>
          <span>{{ statusBlock.text }}</span>
        </div>
        <div class="paid-buttons">
          <el-button type="warning" plain :disabled="submitting" @click="onRefund">
            申请退票
          </el-button>
          <el-button type="primary" @click="onShowTicket">
            🎟️ 查看电子票
          </el-button>
        </div>
      </div>
    </div>

    <!-- 电子票弹窗 -->
    <el-dialog v-model="ticketDialog" title="电子票" width="420px" align-center>
      <div v-loading="ticketLoading" class="ticket-dialog">
        <template v-if="ticketInfo">
          <div class="qr-frame">
            <div class="qr-stub">
              <div class="qr-stub-title">{{ order?.movieTitle }}</div>
              <div class="qr-stub-meta">{{ order?.hallName }} · {{ order?.startTime ? dayjs(order.startTime).format('MM-DD HH:mm') : '' }}</div>
              <div class="qr-stub-seats">{{ order?.seatDesc }}</div>
              <!-- P0-5: 用 qrcode 库渲染的真 QR, 验票端/手机扫码可直接入场 -->
              <img v-if="ticketQrUrl" :src="ticketQrUrl" alt="电子票二维码" class="qr-real" />
              <div class="qr-stub-exp">请出示给验票员扫码入场</div>
              <div class="qr-stub-exp">过期时间: {{ ticketInfo.expAt }} · 一次性使用</div>
            </div>
          </div>
          <div class="ticket-payload">
            <div class="payload-row">
              <span class="payload-label">orderNo</span>
              <span class="payload-value mono">{{ ticketInfo.orderNo }}</span>
            </div>
            <div class="payload-row">
              <span class="payload-label">payload</span>
              <span class="payload-value mono small">{{ ticketInfo.payload }}</span>
            </div>
            <div class="payload-row">
              <span class="payload-label">sig</span>
              <span class="payload-value mono small">{{ ticketInfo.sig }}</span>
            </div>
            <div class="payload-row">
              <span class="payload-label">expAt</span>
              <span class="payload-value mono">{{ ticketInfo.expAt }}</span>
            </div>
            <div class="payload-hint">
              验票端点：<code>GET /api/tickets/verify?payload=...&sig=...</code>（一次性, 第二次将返回"已使用"）
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="ticketDialog = false">关闭</el-button>
      </template>
    </el-dialog>
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
  align-items: center;
  flex-wrap: wrap;
}

.actions.success {
  justify-content: space-between;
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

.paid-buttons {
  display: flex;
  gap: 10px;
}

/* --- 退款中 / 已退款 横幅 --- */
.status-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-radius: var(--radius-md);
  font-size: 14px;
  font-weight: 600;
}

.status-banner.status-refunding {
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.3);
  color: var(--accent-gold-light);
}

.status-banner.status-refunded {
  background: rgba(148, 163, 184, 0.08);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
}

.banner-icon {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--bg-elevated);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(245, 158, 11, 0.3);
  border-top-color: var(--accent-gold);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* --- 电子票弹窗 --- */
.ticket-dialog {
  padding: 4px 0;
}

.qr-frame {
  display: flex;
  justify-content: center;
  margin-bottom: 16px;
}

.qr-stub {
  width: 100%;
  max-width: 320px;
  border: 1px dashed var(--border-color);
  border-radius: var(--radius-md);
  padding: 18px;
  background: var(--bg-tertiary);
  text-align: center;
}

.qr-stub-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 4px;
}

.qr-stub-meta {
  font-size: 12px;
  color: var(--text-muted);
  margin-bottom: 6px;
}

.qr-stub-seats {
  font-size: 14px;
  color: var(--accent-gold-light);
  font-weight: 600;
  margin-bottom: 14px;
}

.qr-real {
  display: block;
  width: 220px;
  height: 220px;
  margin: 0 auto 14px;
  background: #fff;
  padding: 8px;
  border-radius: 6px;
  box-shadow: 0 0 0 1px var(--border-color);
}

.qr-stub-exp {
  font-size: 11px;
  color: var(--text-muted);
  margin-top: 4px;
}

.ticket-payload {
  font-size: 12px;
  color: var(--text-secondary);
}

.payload-row {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--border-color);
  align-items: flex-start;
}

.payload-label {
  flex: 0 0 70px;
  color: var(--text-muted);
  font-size: 12px;
}

.payload-value {
  flex: 1;
  word-break: break-all;
  font-family: 'Consolas', 'Monaco', monospace;
}

.payload-value.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}

.payload-value.small {
  font-size: 11px;
  color: var(--text-muted);
}

.payload-hint {
  margin-top: 10px;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.5;
}

.payload-hint code {
  background: var(--bg-elevated);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 10px;
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

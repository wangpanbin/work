<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { Grid, Warning } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import QRCode from 'qrcode'
import { orderDetail, pay, cancel, refund, getTicket } from '../api/order'
import type { OrderVO, TicketVO } from '../api/order'
import Countdown from '../components/Countdown.vue'
import { useNow } from '../composables/useNow'
import { ROUTE_PATHS, seatPath } from '../constants/routes'
import { ORDER_STATUS_VIEW, BUTTON_BY_ACTION, type OrderStatusCode } from './order/constants'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
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
    ElMessage.error(t('payment.orderNoMissing'))
    router.push(ROUTE_PATHS.home)
    return
  }
  // 首拉失败不再让整个 onMounted 抛出去(2026-10-24):
  // 根节点是 v-if="order" → 失败就是**一整页空白**, 没有报错、没有重试入口。
  try {
    await load()
  } catch {
    // 错误已记进 loadError —— 页面渲染错误态 + 重试按钮
  }
})

/** 加载失败时的错误文案(渲染「加载失败 + 重试」而不是白屏)。 */
const loadError = ref<string | null>(null)

async function load() {
  try {
    order.value = await orderDetail(orderNo)
    loadError.value = null
  } catch (e) {
    loadError.value = t('payment.loadFailed')
    throw e
  }
}

/** 错误态上的「重试」—— 重新拉一次订单详情。 */
async function reload() {
  try {
    await load()
  } catch {
    // 错误态已经更新, 保持在页面上
  }
}

// 时钟走 useNow —— 之前这里调的是 dayjs()(非响应式), remaining 只在 order.value
// 变化时重算, 于是「支付窗口已过」永远不会自己变 true, 超时自动跳转形同虚设。
const { now } = useNow(1000)

const remaining = computed(() => {
  if (!order.value || order.value.status !== 0) return 0
  return Math.max(0, dayjs(order.value.expireAt).diff(now.value, 'second'))
})

const expired = computed(() => remaining.value === 0 && order.value?.status === 0)

/**
 * 告急(≤60s) —— 模板只读这个**布尔量**, 不直接读 remaining。
 *
 * Vue 的 computed 在值没变时不会触发渲染, 所以读布尔量 → 每秒重算但只在
 * 跨过 60 秒那一刻触发一次重渲; 直接读 remaining(数字)→ 每秒重渲整页。
 * 倒计时文本仍由 Countdown.vue 渲染(它自带定时器), 父组件不参与。
 */
const urgent = computed(() => remaining.value <= 60)

// P0-4: 待支付订单超时后, 不再让用户卡在支付页 — 3s 后自动跳订单列表
// 用 setTimeout 而非 immediate, 给用户一个看到"已超时"状态的窗口期
//
// 2026-10-24: 定时器句柄要存下来并在离开页面时清掉 —— 原先它从不取消,
// 于是用户在这 3 秒窗口里点「重新选座」跳去选座页, 那个还活着的定时器
// 会把他从选座页**拽回**订单列表。
let redirectTimer: ReturnType<typeof setTimeout> | null = null

function redirectToOrders() {
  if (autoRedirected.value) return
  autoRedirected.value = true
  ElMessage.warning(t('payment.expiredWarn'))
  redirectTimer = setTimeout(() => router.push(ROUTE_PATHS.orderList), 3000)
}

/** 取消超时自动跳转 —— 用户主动离开前必须调用, 否则会被定时器拽走。 */
function clearRedirectTimer() {
  if (redirectTimer) {
    clearTimeout(redirectTimer)
    redirectTimer = null
  }
}

onUnmounted(() => {
  clearRedirectTimer()
})

watch(expired, (isExpired) => {
  if (isExpired) redirectToOrders()
})

/** 距离场次开始还剩多久（用于退票按钮的可见性） */
const sessionNotStarted = computed(() => {
  if (!order.value?.startTime) return false
  return dayjs(order.value.startTime).isAfter(dayjs())
})

/** 当前订单的状态视图(取常量里的元数据), 模板内复用 bannerCopyKey / tagType / actions */
function viewOf(status: OrderStatusCode) {
  return ORDER_STATUS_VIEW[status]
}

async function onPay() {
  submitting.value = true
  try {
    await pay(orderNo)
    ElMessage.success(t('payment.paySuccess'))
    await load()
  } catch {
    // 拦截器已弹错误, 不再重复
  } finally {
    submitting.value = false
  }
}

async function onCancel() {
  try {
    await ElMessageBox.confirm(
      t('payment.cancelDialogConfirm'),
      t('payment.cancelDialogTitle'),
      {
        confirmButtonText: t('payment.cancelDialogOk'),
        cancelButtonText: t('payment.cancelDialogCancel'),
        type: 'warning',
      },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    await cancel(orderNo)
    ElMessage.success(t('payment.cancelSuccess'))
    await load()
  } catch {
    // 拦截器已弹错误
  } finally {
    submitting.value = false
  }
}

async function onRefund() {
  if (!sessionNotStarted.value) {
    ElMessage.warning(t('order.refundFailStarted'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('order.refundConfirm', { movie: order.value?.movieTitle || '', seats: order.value?.seatDesc || '' }),
      t('order.refundTitle'),
      { confirmButtonText: t('order.refundOk'), cancelButtonText: t('order.refundCancel'), type: 'warning' },
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    await refund(orderNo)
    ElMessage.success(t('order.refundSuccess'))
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
    ElMessage.error(t('payment.qrFailed'))
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
  // 主动离开前取消超时自动跳转 —— 否则那个还活着的定时器会把用户从选座页拽回来
  clearRedirectTimer()
  router.push(seatPath(order.value.sessionId))
}

/**
 * 票根存根上的装饰条码 —— 纯装饰, 不是可扫描的条码, 宽度写死只为"像票"。
 * 真正的票根信息在上面的二维码弹窗里(那是可扫的)。
 */
const barcodeBars = [2, 1, 3, 1, 2, 1, 1, 3, 2, 1, 2, 3, 1, 2, 1, 3]
</script>

<template>
  <!-- 加载失败: 错误态 + 重试, 不再是一整页空白(2026-10-24) -->
  <div v-if="loadError && !order" class="load-error" role="alert">
    <p class="load-error-text">{{ loadError }}</p>
    <el-button type="primary" @click="reload">{{ t('common.retry') }}</el-button>
  </div>
  <div v-if="order" class="payment">
    <div class="payment-card stub-perf-x">
      <!-- Header -->
      <div class="payment-header">
        <div class="header-info">
          <h1 class="title">{{ order.movieTitle }}</h1>
          <div class="meta">{{ order.hallName }} · {{ dayjs(order.startTime).format('YYYY-MM-DD HH:mm') }}</div>
        </div>
        <div class="header-status">
          <el-tag
            :type="viewOf(order.status).tagType"
            size="large" effect="dark"
          >
            {{ order.statusText }}
          </el-tag>
        </div>
      </div>

      <!-- Seat Info -->
      <div class="seat-section">
        <div class="seat-desc">
            <el-icon :size="20"><Grid /></el-icon>
          {{ order.seatDesc }}
        </div>
      </div>

      <div class="divider"></div>

      <!-- Order Details -->
      <div class="details">
        <div class="info-row">
          <span class="label">{{ t('payment.orderNo') }}</span>
          <span class="value mono">{{ order.orderNo }}</span>
        </div>
        <div class="info-row">
          <span class="label">{{ t('payment.seatCount') }}</span>
          <span class="value">{{ order.seatCount }} {{ t('payment.seatUnit') }}</span>
        </div>
        <div v-if="order.status === 0" class="info-row">
          <span class="label">{{ t('payment.countdown') }}</span>
          <span class="value" :class="{ urgent }">
            <el-icon v-if="urgent" :size="16" class="urgent-icon"><Warning /></el-icon>
            <Countdown :expire-at="order.expireAt" :urgent-threshold="60" />
          </span>
        </div>
      </div>

      <div class="divider"></div>

      <!-- Total -->
      <div class="total-row">
        <span class="total-label">{{ order.status === 4 ? t('order.amountRefunded') : t('payment.amountDue') }}</span>
        <span class="total-value">
          <span class="currency">{{ t('order.currency') }}</span>
          <span class="amount">{{ order.totalAmount.toFixed(2) }}</span>
        </span>
      </div>

      <div class="divider"></div>

      <!-- Actions: 统一布局 = 可选 banner/success + 按钮迭代 -->
      <div class="actions" :class="{ success: order.status === 1 }">
        <!-- Banner: REFUNDING -->
        <div v-if="order.status === 3" class="status-banner status-refunding">
          <div class="spinner"></div>
          <span>{{ t(viewOf(order.status).bannerCopyKey) }}</span>
        </div>
        <!-- Banner: REFUNDED -->
        <div v-else-if="order.status === 4" class="status-banner status-refunded">
          <div class="banner-icon">↩</div>
          <span>{{ t(viewOf(order.status).bannerCopyKey) }}</span>
        </div>
        <!-- Success content: PAID -->
        <div v-else-if="order.status === 1" class="success-content">
          <div class="success-icon">✓</div>
          <span>{{ t(viewOf(order.status).bannerCopyKey) }}</span>
        </div>

        <!-- 按钮按 availableActions 迭代 -->
        <template v-for="action in viewOf(order.status).availableActions" :key="action">
          <el-button
            v-if="action === 'pay'"
            type="primary" size="large"
            :loading="submitting"
            class="pay-btn"
            @click="expired ? redirectToOrders() : onPay()"
          >{{ expired ? t('payment.expiredRebook') : t(BUTTON_BY_ACTION.pay.labelKey) }}</el-button>
          <el-button
            v-else-if="action === 'cancel'"
            type="danger" plain
            :disabled="submitting"
            class="cancel-btn"
            @click="onCancel"
          >{{ t(BUTTON_BY_ACTION.cancel.labelKey) }}</el-button>
          <el-button
            v-else-if="action === 'refund'"
            type="warning" plain
            :disabled="submitting"
            @click="onRefund"
          >{{ t(BUTTON_BY_ACTION.refund.labelKey) }}</el-button>
          <el-button
            v-else-if="action === 'viewDetail'"
            type="primary"
            @click="onShowTicket"
          >{{ t(BUTTON_BY_ACTION.viewDetail.labelKey) }}</el-button>
          <el-button
            v-else-if="action === 'viewTicket'"
            type="primary"
            @click="onShowTicket"
          >{{ t(BUTTON_BY_ACTION.viewTicket.labelKey) }}</el-button>
          <el-button
            v-else-if="action === 'rebook'"
            type="primary"
            @click="goSeat"
          >{{ t(BUTTON_BY_ACTION.rebook.labelKey) }}</el-button>
        </template>
      </div>

      <!-- 票根存根: 齿孔撕口在卡片上缘(stub-perf-x), 这里放票号条 + 装饰条码 -->
      <div class="payment-stub">
        <div class="stub-no">
          <span class="stub-no-label">{{ t('payment.orderNo') }}</span>
          <span class="stub-no-value mono">{{ order.orderNo }}</span>
        </div>
        <div class="barcode" aria-hidden="true">
          <i v-for="(w, i) in barcodeBars" :key="i" :style="{ width: w + 'px' }"></i>
        </div>
      </div>
    </div>

    <!-- 电子票弹窗 -->
    <el-dialog v-model="ticketDialog" :title="t('payment.ticketDialogTitle')" width="420px" align-center>
      <div v-loading="ticketLoading" class="ticket-dialog">
        <template v-if="ticketInfo">
          <div class="qr-frame">
            <div class="qr-stub">
              <div class="qr-stub-title">{{ order?.movieTitle }}</div>
              <div class="qr-stub-meta">{{ order?.hallName }} · {{ order?.startTime ? dayjs(order.startTime).format('MM-DD HH:mm') : '' }}</div>
              <div class="qr-stub-seats">{{ order?.seatDesc }}</div>
              <!-- P0-5: 用 qrcode 库渲染的真 QR, 验票端/手机扫码可直接入场 -->
              <img v-if="ticketQrUrl" :src="ticketQrUrl" :alt="t('payment.qrAlt')" class="qr-real" />
              <div class="qr-stub-exp">{{ t('payment.ticketHintShow') }}</div>
              <div class="qr-stub-exp">{{ t('payment.ticketHintExp', { exp: ticketInfo.expAt }) }}</div>
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
              {{ t('payment.ticketHintVerify') }}<code>GET /api/tickets/verify?payload=...&sig=...</code>{{ t('payment.ticketHintOneShot') }}
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="ticketDialog = false">{{ t('common.cancel') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 加载失败态(2026-10-24): 居中的错误提示 + 重试, 不再是一整页空白。
   样式已抽到 main.css 的共享 `.load-error` —— 五个视图共用同一份, 不再各抄一份。 */

.payment {
  max-width: 600px;
  margin: 0 auto;
  animation: fadeInUp 0.5s ease;
}

.payment-card {
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 32px 32px 24px;
  box-shadow: var(--shadow-paper);
  position: relative;
}

/* 齿孔撕口由 main.css 的 .stub-perf-x 提供(卡片上缘的半圆缺口),
   ⚠️ 不要在 .payment-card 上再写 ::before —— 同 specificity 下会覆盖掉
      齿孔的 radial-gradient, 签名装置就消失了。分隔线画在 .payment-stub 上。 */
.payment-header {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-info {
  flex: 1;
  min-width: 0;
}

.title {
  font-family: var(--font-display);
  font-size: 20px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 4px;
}

/* 场次 = 票据数据 → 等宽 */
.meta {
  color: var(--ink-3);
  font-size: 13px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

/* --- Seat Section --- */
.seat-section {
  margin-top: 24px;
}

/* 座位信息是票面主体, 做成淡印底 */
.seat-desc {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  background: var(--accent-wash);
  border: 1px solid var(--rule);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius-md);
  color: var(--ink);
  font-size: 16px;
  font-weight: 600;
}

.divider {
  height: 1px;
  background: var(--rule);
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
  color: var(--ink-3);
  font-size: 14px;
}

.value {
  color: var(--ink);
  font-weight: 500;
}

.value.mono {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 13px;
}

/* 倒计时告急: 原稿用 pulse-glow 金色辉光, 关键帧已随旧主题删除。
   改用"颜色 + 透明度脉冲", 印刷风不发光。 */
.value.urgent {
  color: var(--danger);
  font-weight: 700;
  animation: urgent-blink 1.5s ease-in-out infinite;
}
@keyframes urgent-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

/* --- Total --- */
.total-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.total-label {
  color: var(--ink-2);
  font-size: 14px;
}

.total-value {
  display: flex;
  align-items: baseline;
}

.currency {
  color: var(--accent);
  font-size: 20px;
  font-weight: 600;
}

/* 应付金额 = 票面最重要的一行 → 等宽大号 */
.amount {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 36px;
  font-weight: 600;
  color: var(--accent);
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
  color: var(--ok);
  font-size: 16px;
  font-weight: 600;
}

.success-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--ok);
  color: var(--ink-inverse);
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

/* --- 票根存根（签名装置） --- */
.payment-stub {
  position: relative;
  margin-top: 28px;
  padding-top: 22px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* 撕口下方的分隔线 */
.payment-stub::before {
  content: "";
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--rule);
}
.stub-no {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.stub-no-label {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--ink-3);
}
.stub-no-value {
  font-size: 13px;
  color: var(--ink-2);
  word-break: break-all;
}
.barcode {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 26px;
}
.barcode i {
  display: block;
  background: var(--ink-3);
  height: 100%;
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
  background: var(--accent-wash);
  border: 1px solid var(--rule);
  color: var(--accent);
}

.status-banner.status-refunded {
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  color: var(--ink-2);
}

.banner-icon {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}

.spinner {
  width: 18px;
  height: 18px;
  border: 2px solid var(--accent-wash);
  border-top-color: var(--accent);
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

/* 电子票 = 一张小票根: 虚线框 + 撕口感 */
.qr-stub {
  width: 100%;
  max-width: 320px;
  border: 1px dashed var(--rule-strong);
  border-radius: var(--radius-md);
  padding: 18px;
  background: var(--paper-raised);
  text-align: center;
}

.qr-stub-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 4px;
}

.qr-stub-meta {
  font-size: 12px;
  color: var(--ink-3);
  font-family: var(--font-mono);
  margin-bottom: 6px;
}

.qr-stub-seats {
  font-size: 14px;
  color: var(--accent);
  font-weight: 600;
  font-family: var(--font-mono);
  margin-bottom: 14px;
}

/* ⚠️ 白底是扫码硬要求 —— 禁止改成纸色/浅色/冷靛蓝。
   二维码的 dark/light 也写死在 script 的 QRCode.toDataURL 里
   (dark:'#111827', light:'#ffffff'), 换肤时不要动。
   2026-10-12 冷靛蓝换肤已确认跳过此处: 全站走「浅色内容区」方案正是为了
   让这块白底不成为暗色主题下的刺眼孤岛。 */
.qr-real {
  display: block;
  width: 220px;
  height: 220px;
  margin: 0 auto 14px;
  background: #fff;
  padding: 8px;
  border-radius: var(--radius-sm);
  box-shadow: 0 0 0 1px var(--rule);
}

.qr-stub-exp {
  font-size: 11px;
  color: var(--ink-3);
  margin-top: 4px;
}

.ticket-payload {
  font-size: 12px;
  color: var(--ink-2);
}

.payload-row {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--rule);
  align-items: flex-start;
}

.payload-label {
  flex: 0 0 70px;
  color: var(--ink-3);
  font-size: 12px;
}

.payload-value {
  flex: 1;
  word-break: break-all;
  font-family: var(--font-mono);
}

.payload-value.mono {
  font-family: var(--font-mono);
}

.payload-value.small {
  font-size: 11px;
  color: var(--ink-3);
}

.payload-hint {
  margin-top: 10px;
  font-size: 11px;
  color: var(--ink-3);
  line-height: 1.5;
}

.payload-hint code {
  background: var(--paper-sunk);
  padding: 1px 4px;
  border-radius: 2px;
  font-size: 10px;
  font-family: var(--font-mono);
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

/* ============================================
   暗场票面 (2026-10-20 视觉重构 · 第 2 轮)
   支付页是全站唯一「票已出」的一页 —— 把它做成暗场票面, 与选座页的
   放映厅同族(--hall), 纸面的部分退到次要位置。
   写成一块集中的覆盖而不是逐条改, 是为了让"这张票在暗场里"这件事
   在一处读得完; 上面的规则仍保留纸面写法, 便于对照。
   ⚠️ 身份色只做局部元素(印色条 / 座位块 / 票号印章), 不碰文字与按钮。
   ============================================ */
.payment-card {
  background: var(--hall);
  border-color: var(--hall-rule);
  /* 票根下缘一道支付域印色条: ::before 已被上缘齿孔占用 */
  border-bottom: 4px solid var(--scene-payment);
  box-shadow: var(--shadow-hall);
  color: var(--hall-ink);
}
.payment-card .title {
  color: var(--hall-ink);
}
.payment-card .meta,
.payment-card .label,
.payment-card .total-label,
.payment-card .stub-no-label {
  color: var(--hall-ink-2);
}
.payment-card .value {
  color: var(--hall-ink);
}
.payment-card .divider {
  background: var(--hall-rule);
}
/* 座位块: 支付域身份色的淡底 + 左缘印色条 */
.payment-card .seat-desc {
  background: rgb(var(--scene-payment-rgb) / 0.14);
  border-color: rgb(var(--scene-payment-rgb) / 0.4);
  border-left-color: var(--scene-payment);
  color: var(--hall-ink);
}
/* 倒计时 / 退款中 / 已退款 三条横幅 */
.payment-card .status-banner.status-refunding {
  background: rgb(var(--accent-rgb) / 0.18);
  border-color: var(--hall-rule);
  color: var(--hall-ink);
}
.payment-card .status-banner.status-refunded {
  background: var(--hall-raised);
  border-color: var(--hall-rule);
  color: var(--hall-ink-2);
}
.payment-card .success-content {
  color: var(--hall-ink);
}
.payment-card .banner-icon {
  background: var(--hall-raised);
  border-color: var(--hall-rule);
  color: var(--hall-ink);
}
.payment-card .spinner {
  border-color: var(--hall-rule);
  border-top-color: var(--hall-ink);
}
/* 金额: 暗场里不用浅色字, 用暗场墨 —— 印色交给票据边的印色条与座位块 */
.payment-card .amount,
.payment-card .currency {
  color: var(--hall-ink);
}
/* 票号印章: 支付域身份色 */
.payment-card .stub-no {
  border: 1px solid rgb(var(--scene-payment-rgb) / 0.4);
  background: rgb(var(--scene-payment-rgb) / 0.12);
  border-radius: var(--radius-sm);
  padding: 4px 10px;
}
.payment-card .stub-no-value {
  color: var(--hall-ink);
}
.payment-card .barcode i {
  background: var(--hall-ink-2);
}
/* plain 按钮在暗场上: 语义色落在边框与淡底, 文字走暗场墨 —— 否则暗红/暗金压在深底上读不清 */
.payment-card .el-button--danger.is-plain {
  color: var(--hall-ink);
  border-color: rgb(var(--danger-rgb) / 0.55);
  background: rgb(var(--danger-rgb) / 0.14);
}
.payment-card .el-button--warning.is-plain {
  color: var(--hall-ink);
  border-color: rgb(var(--warn-rgb) / 0.55);
  background: rgb(var(--warn-rgb) / 0.14);
}
</style>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import dayjs from 'dayjs'
import { myOrders, cancel, refund } from '../api/order'
import type { OrderVO } from '../api/order'
import BrandMark from '../components/BrandMark.vue'
import {
  ORDER_STATUS_CODES,
  ORDER_STATUS_FILTERS,
  ORDER_STATUS_VIEW,
  BUTTON_BY_ACTION,
  type OrderStatusCode,
} from './order/constants'

const { t } = useI18n()
const orders = ref<OrderVO[]>([])
const loading = ref(false)

/**
 * P4-2:「全部」tab 的哨兵值。
 *
 * 原来用 `null` 当"全部",但 Element Plus 的 `isPropAbsent = isNil`,
 * 而 `isNil(null) === true` —— 于是 `<el-radio-button :value="null">` 被判定为
 * "没传 value",每次渲染都打一条
 * `[el-radio] label act as value is about to be deprecated` 警告。
 * 换成字符串哨兵即可根治,语义也更明确。
 */
const ALL_STATUS = 'all' as const
type StatusFilter = OrderStatusCode | typeof ALL_STATUS

const activeStatus = ref<StatusFilter>(ALL_STATUS)
// P2-#16: 各状态订单数, 用于 tab 角标
const counts = ref<Record<OrderStatusCode, number>>({ 0: 0, 1: 0, 2: 0, 3: 0, 4: 0 })
const allCount = ref(0)

async function load() {
  loading.value = true
  try {
    const data = await myOrders({
      status: activeStatus.value === ALL_STATUS ? undefined : activeStatus.value,
      page: 1,
      size: 20,
    })
    orders.value = data.records
  } finally {
    loading.value = false
  }
}

// P2-#16: 并发拉各状态 size=1 计数, 让 tab 角标有数据
async function loadCounts() {
  try {
    const [all, ...byStatus] = await Promise.all([
      myOrders({ page: 1, size: 1 }),
      ...ORDER_STATUS_CODES.map((s) => myOrders({ status: s, page: 1, size: 1 })),
    ])
    allCount.value = all.total
    ORDER_STATUS_CODES.forEach((s, i) => { counts.value[s] = byStatus[i].total })
  } catch {
    // 拦截器已提示, 角标保持 0, 不阻断主列表
  }
}

// 当前订单的状态视图(取常量里的元数据), 模板内复用
function viewOf(status: OrderStatusCode) {
  return ORDER_STATUS_VIEW[status]
}

const FILTERS = ORDER_STATUS_FILTERS

async function onCancel(o: OrderVO) {
  // P1-#7: 与 Payment.vue onCancel 保持一致, 加确认弹窗防误触
  try {
    await ElMessageBox.confirm(
      t('order.cancelConfirm', { movie: o.movieTitle, seats: o.seatDesc }),
      t('order.cancelTitle'),
      {
        confirmButtonText: t('order.cancelOk'),
        cancelButtonText: t('order.cancelCancel'),
        type: 'warning',
      },
    )
  } catch {
    return
  }
  try {
    await cancel(o.orderNo)
    ElMessage.success(t('order.cancelSuccess'))
    load()
  } catch {
    // 拦截器已弹错误
  }
}

async function onRefund(o: OrderVO) {
  if (!o.startTime || !dayjs(o.startTime).isAfter(dayjs())) {
    ElMessage.warning(t('order.refundFailStarted'))
    return
  }
  try {
    await ElMessageBox.confirm(
      t('order.refundConfirm', { movie: o.movieTitle, seats: o.seatDesc }),
      t('order.refundTitle'),
      { confirmButtonText: t('order.refundOk'), cancelButtonText: t('order.refundCancel'), type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await refund(o.orderNo)
    ElMessage.success(t('order.refundSuccess'))
    load()
  } catch {
    // 拦截器已弹错误
  }
}

onMounted(() => {
  load()
  loadCounts()
})
</script>

<template>
  <div v-loading="loading" class="order-list">
    <!-- Header -->
    <div class="page-header">
      <h2 class="section-title">{{ t('order.pageTitle') }}</h2>
      <el-radio-group v-model="activeStatus" @change="load" class="filter-tabs">
        <el-radio-button :value="ALL_STATUS">
          {{ t('order.tabAll') }}<el-badge v-if="allCount > 0" :value="allCount" class="tab-badge" />
        </el-radio-button>
        <el-radio-button
          v-for="f in FILTERS"
          :key="f.code"
          :value="f.code"
        >
          {{ t(f.labelKey) }}<el-badge v-if="counts[f.code] > 0" :value="counts[f.code]" class="tab-badge" :type="f.tagType" />
        </el-radio-button>
      </el-radio-group>
    </div>

    <el-empty v-if="!loading && orders.length === 0" :description="t('order.empty')" />

    <div v-else class="orders-list">
      <div
        v-for="(o, idx) in orders"
        :key="o.orderNo"
        class="order-card stub-perf-x"
        :class="'status-' + o.status"
        :style="{ animationDelay: `${idx * 0.06}s` }"
      >
        <div class="order-main">
          <div class="order-icon"><BrandMark :size="24" /></div>
          <div class="order-info">
            <h3 class="movie-title">
              <span v-if="viewOf(o.status).pulse" class="dot-pulse" :title="t('order.pulseTitle')"></span>
              {{ o.movieTitle }}
            </h3>
            <div class="order-meta">
              <span>{{ o.hallName }}</span>
              <span class="sep">·</span>
              <span>{{ dayjs(o.startTime).format('MM-DD HH:mm') }}</span>
              <span class="sep">·</span>
              <span>{{ o.seatDesc }}</span>
            </div>
          </div>
          <div class="order-status">
            <el-tag :type="viewOf(o.status).tagType" effect="dark" round>
              {{ o.statusText || t(viewOf(o.status).labelKey) }}
            </el-tag>
          </div>
        </div>

        <div class="order-footer">
          <div class="footer-left">
            <span class="time-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
                <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
              </svg>
            </span>
            <span class="created-at">{{ dayjs(o.createdAt).format('YYYY-MM-DD HH:mm') }}</span>
          </div>
          <!-- 票号条: 票根主题的签名装置, 等宽排印 -->
          <div class="stub-no">
            <span class="stub-no-label">{{ t('payment.orderNo') }}</span>
            <span class="stub-no-value">{{ o.orderNo }}</span>
          </div>
          <div class="footer-middle">
            <span class="amount-label">{{ o.status === 4 ? t('order.amountRefunded') : t('order.amountTotal') }}</span>
            <span class="amount-value">
              <span class="currency">{{ t('order.currency') }}</span>{{ o.totalAmount.toFixed(2) }}
            </span>
          </div>
          <div class="footer-right">
            <template v-for="action in viewOf(o.status).availableActions" :key="action">
              <el-button
                v-if="action === 'pay'"
                class="btn-action is-fixed"
                type="primary"
                @click="$router.push({ name: 'payment', query: { orderNo: o.orderNo } })"
              >{{ t(BUTTON_BY_ACTION.pay.labelKey) }}</el-button>
              <el-button
                v-else-if="action === 'cancel'"
                class="btn-action is-fixed"
                type="danger"
                plain
                @click="onCancel(o)"
              >{{ t(BUTTON_BY_ACTION.cancel.labelKey) }}</el-button>
              <el-button
                v-else-if="action === 'viewDetail'"
                class="btn-action is-fixed"
                link
                @click="$router.push({ name: 'payment', query: { orderNo: o.orderNo } })"
              >{{ t(BUTTON_BY_ACTION.viewDetail.labelKey) }} →</el-button>
              <el-button
                v-else-if="action === 'refund'"
                class="btn-action is-fixed"
                type="warning"
                plain
                @click="onRefund(o)"
              >{{ t(BUTTON_BY_ACTION.refund.labelKey) }}</el-button>
              <el-button
                v-else-if="action === 'rebook'"
                class="btn-action is-fixed"
                link
                @click="$router.push(`/seat/${o.sessionId}`)"
              >{{ t(BUTTON_BY_ACTION.rebook.labelKey) }} →</el-button>
              <!-- 'viewTicket' 在 OrderList 不渲染(Payment 专属动作) -->
            </template>
            <!-- REFUNDING 退款的 disabled tooltip: OrderList 局部 UI 细节, 不入 OrderStatusView -->
            <el-tooltip
              v-if="o.status === 3"
              :content="t('order.refundingTip')"
              placement="top"
            >
              <el-button class="btn-action is-fixed" disabled>{{ t('order.refundingBtn') }}</el-button>
            </el-tooltip>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.order-list {
  animation: fadeInUp 0.5s ease;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 16px;
}

.page-header .section-title {
  margin-bottom: 0;
}

/* --- Orders List --- */
.orders-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 订单卡 = 一张票根: 上缘齿孔(主.css 的 .stub-perf-x)+ 底部票号条 */
.order-card {
  position: relative;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  overflow: hidden;
  box-shadow: var(--shadow-paper);
  transition: border-color var(--transition-normal);
  animation: fadeInUp 0.4s ease both;
}

/* 印刷风不做悬浮位移, 只提亮边框 */
.order-card:hover {
  border-color: var(--rule-strong);
}

.order-card.status-2 {
  opacity: 0.75;
}

/* 待支付订单: 左侧印色边 + 标题前红点脉冲, 提醒用户及时支付 */
.order-card.status-0 {
  border-left: 3px solid var(--accent);
}
.dot-pulse {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--danger);
  margin-right: 8px;
  vertical-align: middle;
  animation: pulse-red-dot 1.5s ease-in-out infinite;
}
@keyframes pulse-red-dot {
  0%, 100% { box-shadow: 0 0 0 0 rgba(161, 39, 28, 0.5); }
  50% { box-shadow: 0 0 0 8px rgba(161, 39, 28, 0); }
}

.order-main {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px 24px 20px;
  border-bottom: 1px dashed var(--rule);
}

.order-icon {
  font-size: 32px;
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--paper-sunk);
  border: 1px solid var(--rule);
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.order-info {
  flex: 1;
  min-width: 0;
}

.movie-title {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 6px;
}

.order-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-3);
  font-size: 13px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  flex-wrap: wrap;
}

.order-meta .sep {
  color: var(--rule-strong);
}

.order-status {
  flex-shrink: 0;
}

/* --- Footer --- */
.order-footer {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 24px;
  background: var(--paper);
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-3);
  font-size: 12px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

/* 票号条: 与落款同排, 等宽排印 —— 票根上"票号"永远在角落 */
.stub-no {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 2px 8px;
  border: 1px solid var(--rule);
  border-radius: var(--radius-sm);
  background: var(--paper-raised);
}
.stub-no-label {
  font-size: 10px;
  letter-spacing: 1px;
  color: var(--ink-3);
}
.stub-no-value {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
}

.time-icon {
  display: flex;
  color: var(--ink-3);
}

.footer-middle {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.amount-label {
  color: var(--ink-3);
  font-size: 13px;
}

/* 金额 = 票面数据 → 等宽 */
.amount-value {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 22px;
  font-weight: 600;
  color: var(--accent);
}

.amount-value .currency {
  font-size: 14px;
}

.footer-right {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* ============================================
   按钮视觉一致性 —— 统一尺寸规格
   规则: 所有按钮变体(primary/plain/link/disabled、
   有无角标)共用同一套盒子尺寸; 正常/悬停/按下/禁用
   各状态只改颜色, 不允许改变宽高/padding/字号。
   ============================================ */

/* --- 状态筛选 tabs (el-radio-button) ---
   固定宽高: 文字 2/3 字、有无角标都不再影响尺寸;
   inline-flex 让文字与角标作为整体居中。 */
.filter-tabs :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 112px;
  height: 36px;
  padding: 0;
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  line-height: 1;
  box-sizing: border-box;
  white-space: nowrap;
}

/* --- 卡片操作按钮 (el-button) ---
   复用全局 .btn-action.is-fixed (见 src/styles/main.css),
   本页只保留响应式覆盖, 不再重复定义尺寸/padding/font/border。 */

/* --- Responsive --- */
@media (max-width: 768px) {
  .order-main {
    padding: 16px;
    flex-wrap: wrap;
  }
  .order-footer {
    padding: 14px 16px;
    flex-wrap: wrap;
    gap: 12px;
  }
  .footer-middle {
    order: 3;
    flex-basis: 100%;
  }
  .amount-value {
    font-size: 18px;
  }
  /* 状态 tabs 换行后退化为独立 chip: 宽度仍统一,
     各自带完整圆角, 间距由 gap 控制 */
  .filter-tabs {
    flex-wrap: wrap;
    gap: 8px;
  }
  .filter-tabs :deep(.el-radio-button) {
    margin: 0 !important;
  }
  .filter-tabs :deep(.el-radio-button__inner) {
    width: 104px;
    border-radius: var(--radius-md);
  }
}

@media (max-width: 480px) {
  .order-main {
    gap: 12px;
  }
  .order-icon {
    width: 44px;
    height: 44px;
    font-size: 24px;
  }
  .footer-right {
    flex-basis: 100%;
  }
  /* flex:1 + min-width:0: 等分整行宽度, 不受文字长短
     (link 按钮的箭头/字数) 影响, 保证两个按钮像素级等宽 */
  .footer-right .btn-action {
    flex: 1 1 0%;
    min-width: 0;
  }
}

/* P2-#16: tab 角标 — 与文字的间距由 .el-radio-button__inner 的 gap 统一控制,
   角标自身尺寸固定, 不随数字位数/有无角标改变 tab 大小 */
.tab-badge :deep(.el-badge__content) {
  font-size: 10px;
  font-weight: 500;
  height: 16px;
  line-height: 16px;
  padding: 0 5px;
  border: none;
}
</style>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { myOrders, cancel } from '../api/order'
import type { OrderVO } from '../api/order'

const orders = ref<OrderVO[]>([])
const loading = ref(false)
const activeStatus = ref<number | null>(null)

async function load() {
  loading.value = true
  try {
    const data = await myOrders({ status: activeStatus.value ?? undefined, page: 1, size: 20 })
    orders.value = data.records
  } finally {
    loading.value = false
  }
}

async function onCancel(o: OrderVO) {
  try {
    await cancel(o.orderNo)
    ElMessage.success('已取消')
    load()
  } catch (e: unknown) {
    ElMessage.error((e as { message?: string })?.message || '取消失败')
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="order-list">
    <!-- Header -->
    <div class="page-header">
      <h2 class="section-title">📋 我的订单</h2>
      <el-radio-group v-model="activeStatus" @change="load" class="filter-tabs">
        <el-radio-button :value="null">全部</el-radio-button>
        <el-radio-button :value="0">待支付</el-radio-button>
        <el-radio-button :value="1">已支付</el-radio-button>
        <el-radio-button :value="2">已取消</el-radio-button>
      </el-radio-group>
    </div>

    <el-empty v-if="!loading && orders.length === 0" description="暂无订单记录" />

    <div v-else class="orders-list">
      <div
        v-for="(o, idx) in orders"
        :key="o.orderNo"
        class="order-card"
        :class="'status-' + o.status"
        :style="{ animationDelay: `${idx * 0.06}s` }"
      >
        <div class="order-main">
          <div class="order-icon">🎬</div>
          <div class="order-info">
            <h3 class="movie-title">{{ o.movieTitle }}</h3>
            <div class="order-meta">
              <span>{{ o.hallName }}</span>
              <span class="sep">·</span>
              <span>{{ dayjs(o.startTime).format('MM-DD HH:mm') }}</span>
              <span class="sep">·</span>
              <span>{{ o.seatDesc }}</span>
            </div>
          </div>
          <div class="order-status">
            <el-tag :type="o.status === 1 ? 'success' : o.status === 2 ? 'info' : 'warning'" effect="dark" round>
              {{ o.statusText }}
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
          <div class="footer-middle">
            <span class="amount-label">合计</span>
            <span class="amount-value">
              <span class="currency">￥</span>{{ o.totalAmount.toFixed(2) }}
            </span>
          </div>
          <div class="footer-right">
            <el-button v-if="o.status === 0" type="primary" @click="$router.push({ name: 'payment', query: { orderNo: o.orderNo } })">
              去支付
            </el-button>
            <el-button v-if="o.status === 0" type="danger" plain @click="onCancel(o)">取消订单</el-button>
            <el-button v-if="o.status === 1" link @click="$router.push({ name: 'payment', query: { orderNo: o.orderNo } })">
              查看详情 →
            </el-button>
            <el-button v-if="o.status === 2" link @click="$router.push(`/seat/${o.sessionId}`)">
              重新选座 →
            </el-button>
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
  gap: 16px;
}

.order-card {
  background: var(--bg-secondary);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: all var(--transition-normal);
  animation: fadeInUp 0.4s ease both;
}

.order-card:hover {
  border-color: rgba(245, 158, 11, 0.3);
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}

.order-card.status-2 {
  opacity: 0.7;
}

.order-main {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border-subtle);
}

.order-icon {
  font-size: 32px;
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(245, 158, 11, 0.1);
  border-radius: var(--radius-md);
  flex-shrink: 0;
}

.order-info {
  flex: 1;
  min-width: 0;
}

.movie-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 6px;
}

.order-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 13px;
  flex-wrap: wrap;
}

.order-meta .sep {
  color: var(--border-color);
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
  background: var(--bg-elevated);
}

.footer-left {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: 12px;
}

.time-icon {
  display: flex;
  color: var(--text-muted);
}

.footer-middle {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.amount-label {
  color: var(--text-muted);
  font-size: 13px;
}

.amount-value {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  color: var(--accent-red);
}

.amount-value .currency {
  font-size: 14px;
}

.footer-right {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

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
  .footer-right .el-button {
    flex: 1;
  }
}
</style>
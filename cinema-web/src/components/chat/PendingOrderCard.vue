<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { ActionCardVO } from '../../types'
import { buildPayRoute, payWindowSeconds } from './orderProposalCard'

/**
 * 待支付卡 (ADR-0004)。
 *
 * <p>锁座成功后由聊天窗口追加 —— 它**不来自模型**, 是前端根据确认接口的返回
 * 本地拼出来的. 它只做一件事: 告诉用户订单已建、座位已锁 15 分钟, 并给出
 * 去支付页的入口。
 *
 * <p>不自动跳转: 用户可能还想接着聊, 强制跳走会打断对话上下文
 * (`/payment` 路由下聊天浮窗本来就会自动收起, 见 `useChatContext`)。
 */
const props = defineProps<{ card: ActionCardVO }>()

const router = useRouter()
const { t } = useI18n()

const now = ref(Date.now())
let timer: number | undefined
if (typeof window !== 'undefined') {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
}
onUnmounted(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

const remain = computed(() => payWindowSeconds(props.card.orderExpireAt, now.value))
const minutes = computed(() => Math.max(0, Math.ceil(remain.value / 60)))
const expired = computed(() => props.card.orderExpireAt != null && remain.value <= 0)

function onPay() {
  const route = buildPayRoute(props.card.orderNo)
  if (route) router.push(route)
}
</script>

<template>
  <div class="pending-card" :class="{ expired }">
    <div class="pending-card-header">
      <span class="pending-card-type">{{ t('chat.pendingOrder.title') }}</span>
      <span class="pending-card-price" v-if="card.totalAmount != null">¥{{ card.totalAmount }}</span>
    </div>
    <div class="pending-card-body">
      <div class="pending-card-title">{{ card.movieTitle ?? t('chat.cardFallbackTitle') }}</div>
      <div class="pending-card-meta" v-if="card.hallName">{{ card.hallName }} · {{ card.startTime }}</div>
      <div class="pending-card-meta" v-if="card.seatDesc">
        {{ t('chat.pendingOrder.seatsLabel') }}: {{ card.seatDesc }}
      </div>
      <div class="pending-card-meta" v-if="card.orderNo">
        {{ t('chat.pendingOrder.orderLabel') }}: {{ card.orderNo }}
      </div>
    </div>
    <div class="pending-card-tip" v-if="expired">{{ t('chat.pendingOrder.expiredTip') }}</div>
    <div class="pending-card-tip" v-else>
      {{ t('chat.pendingOrder.payWindowTip', { minutes }) }}
    </div>
    <button class="pending-card-btn" type="button" :disabled="expired" @click.stop="onPay">
      {{ t('chat.pendingOrder.goPayBtn') }}
    </button>
  </div>
</template>

<style scoped>
.pending-card {
  border: 1px solid var(--ok);
  border-radius: var(--radius-lg);
  padding: 10px 12px;
  background: var(--paper-raised);
}
.pending-card.expired {
  border-color: var(--rule);
}
.pending-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.pending-card-type {
  font-size: 11px;
  color: var(--ink-3);
  letter-spacing: 0.5px;
}
.pending-card-price {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  color: var(--ok);
  font-size: 14px;
}
.pending-card-body {
  margin-bottom: 8px;
}
.pending-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.pending-card-meta {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
  margin-top: 2px;
}
.pending-card-tip {
  font-size: 11px;
  color: var(--warn);
  margin-bottom: 8px;
}
.pending-card-btn {
  background: var(--accent);
  color: var(--ink-inverse);
  border: none;
  border-radius: var(--radius-md);
  padding: 6px 12px;
  font-size: 12px;
  cursor: pointer;
  width: 100%;
  transition: background-color var(--transition-fast);
}
.pending-card-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}
.pending-card-btn:disabled {
  background: var(--accent-disabled);
  cursor: not-allowed;
}
</style>

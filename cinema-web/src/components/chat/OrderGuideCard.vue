<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import type { ActionCardVO } from '../../types'
import { buildGuideRoute } from './orderProposalCard'

/**
 * 取消/退票**引导卡**(v0.4.0 IT-06)。
 *
 * <p>用户说「帮我取消那单」/「我想退票」时, 助手出这张卡 —— 它**只做引导**:
 * 把用户带到订单页, 让他自己点取消/退票。
 *
 * <p>**助手不执行**(ADR-0002): 助手没有任何写工具, `ChatTools` 里也没有 cancel/refund。
 * 这张卡上**没有任何可提交的载荷** —— 没有座位、没有订单号, 只有「去哪儿」和「做什么」。
 * 取消与退票的真正入口仍是订单页上的既有按钮, 走既有端点。
 */
const props = defineProps<{ card: ActionCardVO }>()

const router = useRouter()
const { t } = useI18n()

const isRefund = computed(() => props.card.guideAction === 'REFUND')

const buttonLabel = computed(() =>
  isRefund.value ? t('chat.orderGuide.refundBtn') : t('chat.orderGuide.cancelBtn'),
)

const hint = computed(() => {
  if (props.card.guideHint) return props.card.guideHint
  return isRefund.value ? t('chat.orderGuide.refundHint') : t('chat.orderGuide.cancelHint')
})

function onGo() {
  router.push(buildGuideRoute(props.card))
}
</script>

<template>
  <div class="guide-card">
    <div class="guide-card-header">
      <span class="guide-card-type">{{ t('chat.cardTypeOrderGuide') }}</span>
    </div>
    <div class="guide-card-body">
      <div class="guide-card-title">{{ card.movieTitle ?? t('chat.cardFallbackTitle') }}</div>
      <div class="guide-card-meta" v-if="card.hallName">{{ card.hallName }} · {{ card.startTime }}</div>
      <div class="guide-card-meta" v-if="card.seatDesc">
        {{ t('chat.cardSeatsLabel') }}: {{ card.seatDesc }}
      </div>
      <div class="guide-card-meta" v-if="card.orderNo">
        {{ t('chat.pendingOrder.orderLabel') }}: {{ card.orderNo }}
      </div>
    </div>
    <div class="guide-card-tip">{{ hint }}</div>
    <button class="guide-card-btn" type="button" @click.stop="onGo">
      {{ card.actionLabel ?? buttonLabel }}
    </button>
  </div>
</template>

<style scoped>
.guide-card {
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 10px 12px;
  background: var(--paper-raised);
}
.guide-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.guide-card-type {
  font-size: 11px;
  color: var(--ink-3);
  letter-spacing: 0.5px;
}
.guide-card-body {
  margin-bottom: 8px;
}
.guide-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.guide-card-meta {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
  margin-top: 2px;
}
.guide-card-tip {
  font-size: 11px;
  color: var(--ink-2);
  margin-bottom: 8px;
}
.guide-card-btn {
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
.guide-card-btn:hover {
  background: var(--accent-hover);
}
</style>

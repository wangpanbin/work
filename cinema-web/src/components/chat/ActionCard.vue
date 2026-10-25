<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import type { ActionCardVO } from '../../types'
import { buildSeatRoute } from './actionCardRoute'
import { cardTypeLabelKey } from './cardRegistry'

const props = defineProps<{
  card: ActionCardVO
}>()

const router = useRouter()
const { t } = useI18n()

/** 卡型文案: 登记过的走 i18n, 没登记的原样显示服务端枚举(不至于空白)。 */
const typeLabel = computed(() => {
  const key = cardTypeLabelKey(props.card.type)
  return key ? t(key) : (props.card.type ?? '')
})

/**
 * 跳到选座流程并预选座位(spec §7.2 + ADR-0002)。
 */
function onClick() {
  router.push(buildSeatRoute(props.card))
}
</script>

<template>
  <!--
    卡片本体可点(鼠标), 但**焦点只落在里面的按钮上**(2026-10-24):
    原先整张卡是 `div @click`, 没有 role / tabindex / keydown —— 键盘用户只能「碰巧」
    tab 到内层按钮、按 Enter 让 click 冒泡上来才走得通, 读屏也只会念成「一组」。
    现在按钮自己绑 onClick, 是唯一焦点落点与无障碍名字。
  -->
  <div class="action-card" @click="onClick">
    <div class="action-card-header">
      <span class="action-card-type">{{ typeLabel }}</span>
      <span class="action-card-price" v-if="card.price != null">¥{{ card.price }}</span>
    </div>
    <div class="action-card-body">
      <div class="action-card-title">{{ card.movieTitle ?? t('chat.cardFallbackTitle') }}</div>
      <div class="action-card-meta" v-if="card.hallName">{{ card.hallName }} · {{ card.startTime }}</div>
      <div class="action-card-meta" v-if="card.seatDesc">{{ t('chat.cardSeatsLabel') }}: {{ card.seatDesc }}</div>
      <div class="action-card-meta" v-if="card.totalAmount != null">{{ t('chat.cardAmountLabel') }}: ¥{{ card.totalAmount }}</div>
    </div>
    <button class="action-card-btn" type="button" @click.stop="onClick">
      {{ card.actionLabel ?? t('chat.cardJumpBtn') }}
    </button>
  </div>
</template>

<style scoped>
.action-card {
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  padding: 10px 12px;
  background: var(--paper-raised);
  cursor: pointer;
  transition: border-color var(--transition-fast);
}
.action-card:hover {
  border-color: var(--accent);
}
.action-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.action-card-type {
  font-size: 11px;
  color: var(--ink-3);
  letter-spacing: 0.5px;
}
/* 票价 / 总价: 等宽 + tabular-nums */
.action-card-price {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  color: var(--accent);
  font-size: 14px;
}
.action-card-body {
  margin-bottom: 8px;
}
.action-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
/* 场次时间 / 座位号 / 金额: 等宽数据 */
.action-card-meta {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
  margin-top: 2px;
}
.action-card-btn {
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
.action-card-btn:hover {
  background: var(--accent-hover);
}
</style>
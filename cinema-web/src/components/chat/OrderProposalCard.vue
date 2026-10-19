<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ActionCardVO, OrderProposalVO } from '../../types'
import { confirmOrderProposal, createOrderProposal } from '../../api/chat'
import {
  buildConfirmRequest,
  buildRepickRequest,
  confirmErrorKey,
  formatCountdown,
  isProposalExpiredCode,
  proposalState,
  remainingSeconds,
} from './orderProposalCard'

/**
 * 一键下单确认卡 (ADR-0004)。
 *
 * <p>它展示的座位与价格**全部来自服务端提案快照** —— 模型说的座位只是提示,
 * 服务端重读位图核验后才落到这张卡上. 确认时也只回传 proposalId。
 *
 * <p>卡上必须显式列出「将取消你当前的待支付单」(ADR-0002 证据 1): 锁座会
 * closePending 掉同场次的旧待支付单, 这个副作用对用户可见才可接受。
 */
const props = defineProps<{ card: ActionCardVO }>()
const emit = defineEmits<{ (e: 'confirmed', vo: OrderProposalVO): void }>()

const { t } = useI18n()

/** 本地副本 —— 「重新找一组」后用新提案覆写 */
const view = ref<ActionCardVO>({ ...props.card })

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

const state = computed(() => proposalState(view.value, now.value))
const countdown = computed(() => formatCountdown(remainingSeconds(view.value.expiresAt, now.value)))

const submitting = ref(false)
const repicking = ref(false)
const errorText = ref('')

/** 已下单 —— 提案被服务端一次性消费掉之后, 原卡必须立刻失效 */
const done = ref(false)

/**
 * 卡态提示 —— 过期 / 不可用时直接显示在卡上。
 *
 * <p>原先 expireTip 只在 onConfirm 里赋值, 而按钮在 state !== 'active' 时是置灰的,
 * 那条分支永远走不到 —— 过期卡只剩一个 00:00 倒计时, 用户不知道该干什么。
 * 现在提示直接由 state 推导, 永远可达。
 */
const stateHint = computed(() => {
  if (done.value) return t('chat.orderProposal.doneTip')
  if (state.value === 'expired') return t('chat.orderProposal.expireTip')
  if (state.value === 'missing') return t('chat.orderProposal.missingTip')
  return ''
})

function applyProposal(vo: OrderProposalVO) {
  view.value = {
    ...view.value,
    proposalId: vo.proposalId,
    sessionId: vo.sessionId,
    seatIndexes: vo.seatIndexes,
    seatDesc: vo.seatDesc,
    price: vo.unitPrice,
    totalAmount: vo.totalAmount,
    movieTitle: vo.movieTitle,
    hallName: vo.hallName,
    startTime: vo.startTime,
    expiresAt: vo.expiresAt,
    pendingCancelOrderNo: vo.pendingCancelOrderNo,
    pendingCancelExpireAt: vo.pendingCancelExpireAt,
    count: vo.seatIndexes?.length ?? view.value.count,
  }
}

async function onConfirm() {
  if (submitting.value || done.value) return
  const req = buildConfirmRequest(view.value)
  if (!req) {
    errorText.value = t('chat.orderProposal.missingTip')
    return
  }
  if (state.value !== 'active') {
    errorText.value = t('chat.orderProposal.expireTip')
    return
  }
  submitting.value = true
  errorText.value = ''
  try {
    const vo = await confirmOrderProposal(req.proposalId)
    // 提案已被服务端 GETDEL 消费掉了 —— 原卡必须立刻失效,
    // 否则用户还能再点一次「确认下单」, 只会拿到 40900
    done.value = true
    emit('confirmed', vo)
  } catch (e: unknown) {
    // 拦截器已弹过一次 toast, 这里只用卡内状态条展示, 不重复弹窗
    const err = e as { code?: number; message?: string }
    const code = err?.code
    if (isProposalExpiredCode(code)) {
      // 提案已过期 / 已被消费 —— 切到过期态, 提示统一由 stateHint 呈现
      view.value = { ...view.value, expiresAt: now.value }
      errorText.value = ''
    } else {
      const key = confirmErrorKey(code)
      errorText.value = key ? t(key) : (err?.message || t('chat.orderProposal.confirmFailed'))
    }
  } finally {
    submitting.value = false
  }
}

async function onRepick() {
  if (repicking.value) return
  const req = buildRepickRequest(view.value)
  if (!req) {
    errorText.value = t('chat.orderProposal.missingTip')
    return
  }
  repicking.value = true
  errorText.value = ''
  try {
    const vo = await createOrderProposal({ sessionId: req.sessionId, count: req.count })
    applyProposal(vo)
  } catch (e: unknown) {
    const err = e as { message?: string }
    errorText.value = err?.message || t('chat.orderProposal.repickFailed')
  } finally {
    repicking.value = false
  }
}
</script>

<template>
  <div class="order-card" :class="{ expired: state !== 'active' }">
    <div class="order-card-header">
      <span class="order-card-type">{{ t('chat.orderProposal.title') }}</span>
      <span class="order-card-price" v-if="view.totalAmount != null">¥{{ view.totalAmount }}</span>
    </div>
    <div class="order-card-body">
      <div class="order-card-title">{{ view.movieTitle ?? t('chat.cardFallbackTitle') }}</div>
      <div class="order-card-meta" v-if="view.hallName">{{ view.hallName }} · {{ view.startTime }}</div>
      <div class="order-card-meta" v-if="view.seatDesc">
        {{ t('chat.orderProposal.seatsLabel') }}: {{ view.seatDesc }}
      </div>
      <div class="order-card-meta" v-if="view.price != null">
        {{ t('chat.orderProposal.unitPriceLabel') }}: ¥{{ view.price }}
      </div>
      <div class="order-card-meta countdown" :class="{ dead: state !== 'active' }">
        {{ t('chat.orderProposal.countdownLabel') }}: {{ countdown }}
      </div>
    </div>

    <div class="order-card-warn" v-if="view.pendingCancelOrderNo">
      {{ t('chat.orderProposal.cancelPendingWarn', { order: view.pendingCancelOrderNo }) }}
    </div>
    <div class="order-card-tip" v-if="!done">{{ t('chat.orderProposal.notGuaranteedTip') }}</div>
    <div class="order-card-hint" v-if="stateHint">{{ stateHint }}</div>
    <div class="order-card-error" v-if="errorText">{{ errorText }}</div>

    <button
      class="order-card-btn"
      type="button"
      :disabled="done || state !== 'active' || submitting"
      @click.stop="onConfirm"
    >
      {{ done ? t('chat.orderProposal.doneBtn') : (submitting ? t('chat.orderProposal.submitting') : t('chat.orderProposal.confirmBtn')) }}
    </button>
    <button
      class="order-card-btn ghost"
      type="button"
      :disabled="repicking || done"
      @click.stop="onRepick"
    >
      {{ repicking ? t('chat.orderProposal.repicking') : t('chat.orderProposal.repickBtn') }}
    </button>
  </div>
</template>

<style scoped>
.order-card {
  border: 1px solid var(--accent);
  border-radius: var(--radius-lg);
  padding: 10px 12px;
  background: var(--paper-raised);
  transition: border-color var(--transition-fast);
}
.order-card.expired {
  border-color: var(--rule);
}
.order-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.order-card-type {
  font-size: 11px;
  color: var(--ink-3);
  letter-spacing: 0.5px;
}
/* 票价 / 总价: 等宽 + tabular-nums */
.order-card-price {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  color: var(--accent);
  font-size: 14px;
}
.order-card-body {
  margin-bottom: 8px;
}
.order-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
/* 场次时间 / 座位号 / 金额: 等宽数据 */
.order-card-meta {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink-2);
  margin-top: 2px;
}
.order-card-meta.countdown {
  color: var(--warn);
}
.order-card-meta.countdown.dead {
  color: var(--ink-3);
}
.order-card-warn {
  font-size: 12px;
  color: var(--danger);
  background: var(--paper-sunk);
  border-left: 3px solid var(--danger);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  margin-bottom: 6px;
}
.order-card-tip {
  font-size: 11px;
  color: var(--ink-3);
  margin-bottom: 8px;
}
.order-card-hint {
  font-size: 12px;
  color: var(--warn);
  margin-bottom: 8px;
}
.order-card-error {
  font-size: 12px;
  color: var(--danger);
  margin-bottom: 8px;
}
.order-card-btn {
  background: var(--accent);
  color: var(--ink-inverse);
  border: none;
  border-radius: var(--radius-md);
  padding: 6px 12px;
  font-size: 12px;
  cursor: pointer;
  width: 100%;
  margin-bottom: 6px;
  transition: background-color var(--transition-fast);
}
.order-card-btn:hover:not(:disabled) {
  background: var(--accent-hover);
}
.order-card-btn:disabled {
  background: var(--accent-disabled);
  cursor: not-allowed;
}
/* 次要按钮: 描边而非实心, 视觉层级低于主按钮 */
.order-card-btn.ghost {
  background: none;
  color: var(--accent);
  border: 1px solid var(--rule-strong);
  margin-bottom: 0;
}
.order-card-btn.ghost:hover:not(:disabled) {
  background: var(--accent-wash);
  color: var(--accent-hover);
}
.order-card-btn.ghost:disabled {
  color: var(--ink-3);
  border-color: var(--rule);
}
</style>

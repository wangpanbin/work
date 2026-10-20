<script setup lang="ts">
/**
 * 倒计时子组件 (Phase D-⑰)
 * <p>替代 Payment.vue 里的 setInterval(now, 1000) — 父组件不再订阅 now 整体重渲,
 * 只有本组件的 mm:ss 文本每秒更新。
 * <p>接收 expireAt 时间戳, 每秒计算剩余秒数, 输出 mm:ss 字符串。
 * 过期返回 '00:00', 可配合 :urgent 样式标记。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import dayjs from 'dayjs'

const props = defineProps<{
  /** ISO 时间字符串 / 毫秒时间戳 / Date */
  expireAt: string | number | Date
  urgentThreshold?: number
}>()

const timeStr = ref('--:--')
const expired = ref(false)
/**
 * 每秒自增, 用来给数字当 key 强制重挂 —— 重挂一次 = 一次 CSS 动画重播。
 * 这样「数字在走」就有实感, 而不需要每秒改一遍 class(改 class 只能开/关,
 * 没法重播)。
 */
const tickKey = ref(0)
let timer: number | null = null

function tick() {
  const now = dayjs()
  const expire = dayjs(props.expireAt)
  const diff = expire.diff(now, 'second')
  if (diff <= 0) {
    timeStr.value = '00:00'
    expired.value = true
    tickKey.value += 1
    if (timer != null) {
      clearInterval(timer)
      timer = null
    }
    return
  }
  const m = Math.floor(diff / 60)
  const s = diff % 60
  timeStr.value = `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
  expired.value = false
  tickKey.value += 1
}

/** 紧急态: 过期, 或已进入 urgentThreshold 之内 */
const urgent = computed(
  () =>
    expired.value ||
    (props.urgentThreshold != null &&
      timeStr.value !== '00:00' &&
      timeStr.value <= '01:00'),
)

watch(() => props.expireAt, tick)

onMounted(() => {
  tick()
  timer = window.setInterval(tick, 1000)
})

onBeforeUnmount(() => {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<template>
  <!-- 非紧急态: 数字每秒重挂一次, 播一个 0.24s 的轻微弹跳 —— 「数字在走」的实感。
       紧急态单独一支: 它的常驻闪烁不能被每秒重挂打断(重挂会把闪烁重置回起点)。 -->
  <span v-if="urgent" class="urgent">{{ timeStr }}</span>
  <span v-else :key="tickKey" class="tick">{{ timeStr }}</span>
</template>

<style scoped>
/* 倒计时是票根上最"票据"的一格数据 —— 等宽 + tabular-nums,
   否则秒数跳动时字宽会抖, 视觉上像在闪。 */
span {
  display: inline-block;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.04em;
}

/* 每秒一次的轻微弹跳: 只动 transform, 不动布局, 所以不会推挤旁边的字。 */
span.tick {
  animation: tick-pop 0.24s ease-out;
}
@keyframes tick-pop {
  0% { transform: scale(1.08); }
  100% { transform: scale(1); }
}

span.urgent {
  color: var(--danger);
  font-weight: 700;
  /* 印刷风不发光: 只做透明度脉冲 */
  animation: countdown-blink 1.5s ease-in-out infinite;
}

@keyframes countdown-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.45; }
}

@media (prefers-reduced-motion: reduce) {
  span.urgent,
  span.tick {
    animation: none;
  }
}
</style>

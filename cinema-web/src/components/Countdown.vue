<script setup lang="ts">
/**
 * 倒计时子组件 (Phase D-⑰)
 * <p>替代 Payment.vue 里的 setInterval(now, 1000) — 父组件不再订阅 now 整体重渲,
 * 只有本组件的 mm:ss 文本每秒更新.
 * <p>接收 expireAt 时间戳, 每秒计算剩余秒数, 输出 mm:ss 字符串.
 * 过期返回 '00:00', 可配合 :urgent 样式标记.
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import dayjs from 'dayjs'

const props = defineProps<{
  /** ISO 时间字符串 / 毫秒时间戳 / Date */
  expireAt: string | number | Date
  urgentThreshold?: number
}>()

const timeStr = ref('--:--')
const expired = ref(false)
let timer: number | null = null

function tick() {
  const now = dayjs()
  const expire = dayjs(props.expireAt)
  const diff = expire.diff(now, 'second')
  if (diff <= 0) {
    timeStr.value = '00:00'
    expired.value = true
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
}

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
  <span :class="{ urgent: expired || (urgentThreshold != null && timeStr !== '00:00' && timeStr <= '01:00') }">
    {{ timeStr }}
  </span>
</template>

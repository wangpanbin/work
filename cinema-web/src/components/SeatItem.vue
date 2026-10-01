<script setup lang="ts">
/**
 * 单个座位子组件 (Phase D-⑬)
 * <p>把 SeatSelect 模板里每座 4 次方法调用(statusAt×2 + rowCol×2)收敛到子组件内,
 * 父组件 v-for 只传递 :index, 单个座位的状态变化只触发子组件 update, 不影响其他座位.
 * <p>点击反馈在子组件内处理(用全局 ElMessage), 父组件不必再管 maxSelect 提示.
 * <p>P0-2: conflictFlash 命中时加 .flash class, 做红色脉冲外环, 提示用户"刚被抢走".
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useSeatStore } from '../stores/seat'

const props = defineProps<{ index: number }>()

const { t } = useI18n()
const seatStore = useSeatStore()
const status = computed(() => seatStore.statusAt(props.index))
const pos = computed(() => seatStore.rowCol(props.index))
const isFlashing = computed(() => seatStore.conflictFlash.has(props.index))

/**
 * 无障碍: 5 种座位状态在座位图里主要靠"颜色 + 纹理/虚线/粗细"区分,
 * 屏幕阅读器完全读不出来。title/aria-label 是唯一的非视觉通道, 必须带状态。
 * 复用已有的 legend* 五个 i18n 键(它们本来就是 5 态标签), 不新增键。
 */
const statusLabel = computed(() => {
  switch (status.value) {
    case 'AVAILABLE':
      return t('seat.legendAvailable')
    case 'SELECTED':
      return t('seat.legendSelected')
    case 'LOCKED_MINE':
      return t('seat.legendMine')
    case 'LOCKED_OTHER':
      return t('seat.legendLocked')
    case 'SOLD':
      return t('seat.legendSold')
    default:
      return ''
  }
})

const a11yLabel = computed(
  () => t('seat.rowColFormat', { row: pos.value.row, col: pos.value.col }) + ' · ' + statusLabel.value,
)

function onClick() {
  const ok = seatStore.toggle(props.index)
  if (ok) return
  // 切换失败: 给出原因提示
  if (status.value === 'SOLD' || status.value === 'LOCKED_OTHER') {
    ElMessage.info(t('seat.infoUnavailable'))
  } else if (seatStore.selected.size >= seatStore.maxSelect) {
    ElMessage.warning(t('seat.warnMaxSelect', { n: seatStore.maxSelect }))
  }
}
</script>

<template>
  <div
    :class="['seat', status.toLowerCase(), { mine: status === 'LOCKED_MINE', flash: isFlashing }]"
    :title="a11yLabel"
    :aria-label="a11yLabel"
    :aria-disabled="status === 'SOLD' || status === 'LOCKED_OTHER'"
    role="button"
    @click="onClick"
  >
    {{ pos.col }}
  </div>
</template>

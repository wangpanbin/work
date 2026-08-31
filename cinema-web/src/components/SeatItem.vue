<script setup lang="ts">
/**
 * 单个座位子组件 (Phase D-⑬)
 * <p>把 SeatSelect 模板里每座 4 次方法调用(statusAt×2 + rowCol×2)收敛到子组件内,
 * 父组件 v-for 只传递 :index, 单个座位的状态变化只触发子组件 update, 不影响其他座位.
 * <p>点击反馈在子组件内处理(用全局 ElMessage), 父组件不必再管 maxSelect 提示.
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useSeatStore } from '../stores/seat'

const props = defineProps<{ index: number }>()

const seatStore = useSeatStore()
const status = computed(() => seatStore.statusAt(props.index))
const pos = computed(() => seatStore.rowCol(props.index))

function onClick() {
  const ok = seatStore.toggle(props.index)
  if (ok) return
  // 切换失败: 给出原因提示
  if (status.value === 'SOLD' || status.value === 'LOCKED_OTHER') {
    ElMessage.info('该座位不可选')
  } else if (seatStore.selected.size >= seatStore.maxSelect) {
    ElMessage.warning(`最多选择 ${seatStore.maxSelect} 个座位`)
  }
}
</script>

<template>
  <div
    :class="['seat', status.toLowerCase(), status === 'LOCKED_MINE' ? 'mine' : '']"
    :title="`${pos.row}排${pos.col}座`"
    @click="onClick"
  >
    {{ pos.col }}
  </div>
</template>

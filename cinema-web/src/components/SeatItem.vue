<script setup lang="ts">
/**
 * 单个座位子组件 (Phase D-⑬)
 * <p>把 SeatSelect 模板里每座 4 次方法调用(statusAt×2 + rowCol×2)收敛到子组件内,
 * 父组件 v-for 只传递 :index, 单个座位的状态变化只触发子组件 update, 不影响其他座位。
 * <p>点击反馈在子组件内处理(用全局 ElMessage), 父组件不必再管 maxSelect 提示。
 * <p>P0-2: conflictFlash 命中时加 .flash class, 做红色脉冲外环, 提示用户"刚被抢走"。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useSeatStore, MAX_WATCH } from '../stores/seat'

const props = defineProps<{ index: number; preview?: boolean }>()

/**
 * 拖拽连选的事件出口。
 * 现有 onClick 逻辑、role/tabindex/键盘可达性一律不动 ——
 * 拖拽只是多给父组件两个信号, 单击行为逐字不变。
 */
const emit = defineEmits<{
  (e: 'pointerdown', index: number): void
  (e: 'pointerenter', index: number): void
}>()

const { t } = useI18n()
const seatStore = useSeatStore()
const status = computed(() => seatStore.statusAt(props.index))
const pos = computed(() => seatStore.rowCol(props.index))
const isFlashing = computed(() => seatStore.conflictFlash.has(props.index))

// ==== 座位关注(Seat Watch) ====
// 提醒模式下「被占用的座位」值得等: 它不可选, 但可以关注。关闭模式时下面全部为 false,
// 座位行为与改动前逐字一致(零回归)。
const isWatched = computed(() => seatStore.watched.has(props.index))
/** 只有「被占用的座位」可关注: 已售 / 他人锁定 */
const watchable = computed(
  () => seatStore.watchMode && (status.value === 'SOLD' || status.value === 'LOCKED_OTHER'),
)

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

const a11yLabel = computed(() => {
  const base = t('seat.rowColFormat', { row: pos.value.row, col: pos.value.col })
    + ' · ' + statusLabel.value
  // 关注态必须是屏幕阅读器可读的非视觉通道, 不能只靠角标
  if (!watchable.value) return base
  return base + ' · ' + t(isWatched.value ? 'seat.watchWatching' : 'seat.watchNotWatching')
})

/**
 * 键盘可达性: 座位是 role="button" 的 div, 必须有 tabindex 才能被 Tab 聚焦,
 * 且 Enter / Space 要能触发点击(role=button 的预期键)。
 * 已售 / 他人锁定不可选 → tabindex="-1" 从 Tab 顺序里摘掉, 不制造无意义的焦点停靠。
 *
 * ⚠️ 2026-10-25: `aria-disabled` 必须跟 `focusable` 用同一套判据 ——
 * **提醒模式下被占用的座位是可以点的**(点了就是切换关注,见 onClick),
 * 但 aria-disabled 仍按 `unavailable` 报 true → 读屏用户被告知「这个按钮不可用」,
 * 而它其实能用。`focusable` 早就考虑了 watchable,`aria-disabled` 漏了。
 * 这条是浏览器回归套件 S17 撞出来的: Playwright 看到 aria-disabled="true"
 * 就拒绝点击(它把 aria-disabled 当作「不可交互」)。
 */
const unavailable = computed(() => status.value === 'SOLD' || status.value === 'LOCKED_OTHER')

/**
 * 是否进 Tab 顺序。
 * 普通模式下不可选座位保持 tabindex=-1(不制造无意义焦点停靠, 与改动前一致);
 * 提醒模式下把它们放回焦点序列 —— 否则键盘用户根本无法关注任何座位。
 */
const focusable = computed(() => !unavailable.value || watchable.value)

function onPointerDown() {
  emit('pointerdown', props.index)
}

function onPointerEnter() {
  emit('pointerenter', props.index)
}

function onClick() {
  // 提醒模式 + 被占用座位 → 切换关注, 不走选座
  if (watchable.value) {
    const reason = seatStore.toggleWatch(props.index)
    if (reason === 'limit') {
      ElMessage.warning(t('seat.watchLimitReached', { n: MAX_WATCH }))
    }
    return
  }
  const ok = seatStore.toggle(props.index)
  if (ok) return
  // 切换失败: 给出原因提示
  if (unavailable.value) {
    ElMessage.info(t('seat.infoUnavailable'))
  } else if (status.value === 'LOCKED_MINE') {
    // 我自己锁的座位(2026-10-25 交互流程审查 P1): `toggle` 只放行
    // AVAILABLE / SELECTED, 所以它必然返回 false; 而 `unavailable` 只含
    // SOLD / LOCKED_OTHER, 上限分支也不成立 —— 原先两个提示分支一个都不进,
    // 用户看到一片绿色(我已锁)的座位点下去毫无反应, 只会以为页面卡了。
    ElMessage.info(t('seat.lockedMineHint'))
  } else if (seatStore.selected.size >= seatStore.maxSelect) {
    ElMessage.warning(t('seat.warnMaxSelect', { n: seatStore.maxSelect }))
  }
}
</script>

<template>
  <div
    :class="['seat', status.toLowerCase(), {
      mine: status === 'LOCKED_MINE',
      flash: isFlashing,
      watched: isWatched,
      'drag-preview': preview,
    }]"
    :title="a11yLabel"
    :aria-label="a11yLabel"
    :aria-disabled="unavailable && !watchable"
    :aria-pressed="watchable ? isWatched : undefined"
    :tabindex="focusable ? 0 : -1"
    role="button"
    @click="onClick"
    @pointerdown="onPointerDown"
    @pointerenter="onPointerEnter"
    @keydown.enter.prevent="onClick"
    @keydown.space.prevent="onClick"
  >
    {{ pos.col }}
  </div>
</template>

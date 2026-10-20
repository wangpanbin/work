<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { seatMap } from '../api/seat'
import { lockSeats } from '../api/order'
import { seatConflictOf } from '../api/errorCodes'
import { errorText } from '../i18n/errorText'
import { useUserStore } from '../stores/user'
import { useSeatStore, MAX_WATCH } from '../stores/seat'
import { InfoFilled, Refresh } from '@element-plus/icons-vue'
import { parsePreselectFromQuery } from '../stores/seatEventReducer'
import { computeReleasedWatchHits, formatSeatPositions } from '../stores/seatWatch'
import { createSeatWs, type SeatWsHandle, type WsStatus } from '../utils/ws'
import { useSeatSync } from '../composables/useSeatSync'
import { computeDragRange } from '../utils/seatDragRange'
import SeatItem from '../components/SeatItem.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const seatStore = useSeatStore()
const { t } = useI18n()

// 后端 sessionId 是雪花 ID (19 位, 超过 JS Number.MAX_SAFE_INTEGER=2^53-1=9007199254740991),
// 必须保持 string 才能避免精度丢失 —— 否则 request URL 里最后几位会变 0, 后端查不到
const sessionId = computed(() => String(route.params.sessionId))
const submitting = ref(false)
const refreshing = ref(false)
let wsHandle: SeatWsHandle | null = null

// P0-2: WS 连接状态(从 wsHandle.status 同步过来给 template 用)
const wsStatus = ref<WsStatus>('connecting')

/**
 * 拉取权威座位图。
 *
 * @param preserveSelection 权威重拉时为 true —— 不直接丢弃用户已选座位, 而是逐个用新位图复核。
 *   缺省 false, 保持改动前的语义(手动刷新按钮 / 首次装载仍清空选座)。
 * @returns 本次被剔除的已选座位数(保留模式下才有意义)
 */
async function refresh(preserveSelection = false): Promise<number> {
  refreshing.value = true
  try {
    const m = await seatMap(sessionId.value)
    return seatStore.load(m, { preserveSelection }).length
  } finally {
    refreshing.value = false
  }
}

/**
 * 拖拽连选 —— 纯视图层状态, 不进 Pinia。
 *
 * <p>为什么放在组件本地: 选座状态机(stores/seat.ts)有 35+ 条单测锁着契约,
 * 拖拽只是"按住座位往旁边划"这一层交互, 落座仍然必须走既有的
 * {@code seatStore.toggle(idx)} —— 上限、冲突、乐观更新全由 store 说了算。
 *
 * <p>区间计算是纯函数(utils/seatDragRange.ts), 本仓 vitest 挂不了 SFC,
 * 逻辑必须下沉才可测。
 */
const dragAnchor = ref<number | null>(null)
const dragPreview = ref<Set<number>>(new Set())

/**
 * 悬停预演 —— 不按鼠标, 光是划过也能看见「从这一排的起点划到这儿, 会连选哪几个」。
 *
 * <p><b>与拖拽的关系</b>: 用的是同一个纯函数(utils/seatDragRange.ts)、同一套语义
 * (同行连续可选、撞不可选即截断、受 maxSelect 预算限制)。区别只在锚点:
 * 拖拽的锚点是**按下的那个座位**, 悬停的锚点是**本轮划过这一排时的第一个座位**。
 * 指针换行即换锚点, 指针离开座位图即清空。
 *
 * <p><b>为什么只画 ≥2 座的区间</b>: 单座的区间不需要预演 —— 鼠标停在哪儿,
 * 点的就是哪儿。只有当「这儿能连着拿走一串」时, 预演环才有信息量;
 * 否则指针每动一下就闪一个圈, 变成噪声。
 *
 * <p><b>不落座</b>: 预演只改组件本地的 ref, 不碰 seatStore —— 上限、冲突、
 * 乐观更新仍由 store 说了算(同拖拽连选的做法)。
 */
const hoverAnchor = ref<number | null>(null)
const hoverPreview = ref<Set<number>>(new Set())

function resetHoverPreview() {
  hoverAnchor.value = null
  hoverPreview.value = new Set()
}

function onSeatPointerDown(index: number) {
  // 锚点必须当前可选: 锚在已售/他人锁定/已选上的拖拽不成立
  if (seatStore.statusAt(index) !== 'AVAILABLE') return
  dragAnchor.value = index
  dragPreview.value = new Set([index])
  // 按下即接管预演: 悬停那一轮的锚点与描边要清掉 ——
  // 否则拖拽结束后上一轮的圈还留在屏幕上, 下一轮悬停还会从那个陈旧锚点起算。
  resetHoverPreview()
  window.addEventListener('pointerup', onDragEnd)
  window.addEventListener('pointercancel', onDragCancel)
}

function onSeatPointerEnter(index: number) {
  const cols = seatStore.map?.cols || 1
  // 还能再选几个 = 上限 - 已选
  const budget = Math.max(0, seatStore.maxSelect - seatStore.selected.size)
  const anchor = dragAnchor.value
  if (anchor === null) {
    // 未拖拽: 走悬停预演。锚点 = 本轮划过这一排时的第一个座位(换行即换锚点)。
    const sameRow =
      hoverAnchor.value !== null &&
      Math.floor(hoverAnchor.value / cols) === Math.floor(index / cols)
    if (!sameRow) hoverAnchor.value = index
    const r = computeDragRange(
      hoverAnchor.value as number,
      index,
      cols,
      (i) => seatStore.statusAt(i),
      budget,
    )
    hoverPreview.value = r.indexes.length >= 2 ? new Set(r.indexes) : new Set()
    return
  }
  const r = computeDragRange(anchor, index, cols, (i) => seatStore.statusAt(i), budget)
  dragPreview.value = new Set(r.indexes)
}

/** 松手: 落座。区间只有一个座位时交给既有的 click 逻辑, 免得连点被 toggle 两次。 */
function onDragEnd() {
  const preview = dragPreview.value
  window.removeEventListener('pointerup', onDragEnd)
  window.removeEventListener('pointercancel', onDragCancel)
  dragAnchor.value = null
  dragPreview.value = new Set()
  // 悬停预演也一并清掉: 松手后指针还停在那个座位上, 但预演环属于「上一轮划过」,
  // 留着会让人以为那几座已经被框住了。
  resetHoverPreview()
  if (preview.size <= 1) return
  for (const idx of preview) {
    // 落座前再核一次: 拖拽期间 WS 可能已经改了这张座位的状态
    if (seatStore.statusAt(idx) !== 'AVAILABLE') continue
    seatStore.toggle(idx)
  }
}

/** 取消(系统手势抢走指针): 只清理, 不落座 */
function onDragCancel() {
  window.removeEventListener('pointerup', onDragEnd)
  window.removeEventListener('pointercancel', onDragCancel)
  dragAnchor.value = null
  dragPreview.value = new Set()
  resetHoverPreview()
}

/**
 * 库存四段统计: 可选 / 我已锁 / 他人锁定 / 已售。
 *
 * <p>位图本来就在客户端(soldBitmap / lockBitmap), 直接数就行, 不需要新接口。
 * 数量随 WS 事件实时变化 —— 这就是「实时层」在选座页的落点。
 *
 * <p>四段之和恒等于总座位数(已售优先于锁定, 与 statusAt 的判定顺序一致)。
 */
const seatInventory = computed(() => {
  const map = seatStore.map
  if (!map) return { total: 0, available: 0, mine: 0, locked: 0, sold: 0 }
  const soldBits = seatStore.soldBits
  const lockBits = seatStore.lockBits
  const mineSet = new Set(seatStore.myLockedSeats)
  let sold = 0
  let locked = 0
  let mine = 0
  for (let i = 0; i < map.seatCount; i++) {
    if (soldBits[i]) {
      sold++
      continue
    }
    if (lockBits[i]) {
      if (mineSet.has(i)) mine++
      else locked++
    }
  }
  return {
    total: map.seatCount,
    available: map.seatCount - sold - locked - mine,
    mine,
    locked,
    sold,
  }
})

// ============ 权威座位图重拉(断连补偿 + 释放后收敛)============
//
// 实时同步是「乐观视图」, 后端才是唯一权威。两个触发点:
//   ① 释放事件之后 —— 释放最容易引发「同座位紧跟一次锁座」, 假可选最容易被察觉;
//   ② 断连重连成功之后 —— 断连期间丢失的事件, 这是唯一的补偿点。
//
// 补偿策略(防抖 / 退避 / preserveSelection / 放弃判定)由 useSeatSync 拥有 ——
// 本视图只负责「什么时候触发」与「怎么提示」, 策略不再散落在两处。
const seatSync = useSeatSync({
  refresh,
  isBusy: () => submitting.value || refreshing.value,
  onSeatsTaken: (n) => ElMessage.warning(t('seat.warnSeatsTaken', { n })),
})

// ============ 座位关注: 释放提醒 ============
/**
 * 释放事件后的关注命中提示。
 * 命中 = 关注集合 ∩ 本次释放座位 ∩ 释放后本地状态确认已恢复可选;
 * 多个命中合并成一条(不刷屏); 命中即结束该座位的一次性关注。
 * ⚠️ 提醒是机会提示, 不是预留 —— 文案明确说明可能被别人再次锁定。
 *    用户仍需自行选座并经服务端锁座校验, 系统绝不代为写入。
 */
function notifyReleasedWatchHits(released: number[]) {
  if (seatStore.watched.size === 0) return
  const hits = computeReleasedWatchHits(
    seatStore.watched, released, seatStore.lockBits, seatStore.soldBits,
  )
  if (hits.length === 0) return
  const cols = seatStore.map?.cols || 1
  const text = formatSeatPositions(
    hits, cols,
    (row, col) => t('seat.rowColFormat', { row, col }),
    t('seat.listSeparator'),
  )
  ElMessage.success(t('seat.watchReleased', { list: text }))
  seatStore.clearWatchHits(hits)
}

onMounted(async () => {
  if (!userStore.isLogin) {
    ElMessage.warning(t('seat.warnLogin'))
    router.push('/login')
    return
  }
  await refresh()
  // T7: 应用 preselect(spec §7.2) — 来自 chat 行动卡片的跳转
  // 必须在 refresh() 之后调用(位图未知则冲突判断失效)
  const preselect = parsePreselectFromQuery(route.query.preselect)
  if (preselect.length > 0) {
    seatStore.applyPreselect(preselect)
  }
  wsHandle = createSeatWs(sessionId.value, (evt) => {
    const before = seatStore.selected.size
    // 带 seq: 先按座位丢弃乱序到达的迟到事件(无序号时全部接受, 退化成纯增量)
    seatStore.applyEvent(evt.type, evt.seats, evt.seq)
    // P0-2: WS 事件导致 selected 减少时, 提示用户具体被抢了哪个
    if ((evt.type === 'LOCKED' || evt.type === 'SOLD') && seatStore.selected.size < before) {
      const lostCount = before - seatStore.selected.size
      ElMessage.warning(t('seat.warnSeatsTaken', { n: lostCount }))
    }
    if (evt.type === 'RELEASED') {
      notifyReleasedWatchHits(evt.seats)
      seatSync.reconcile('release')
    }
  })
  // 同步初始状态, 后续 watch 自动更新
  wsStatus.value = wsHandle.status.value
  // 断连重连 = 断连期间丢失事件的唯一补偿点 → 立刻做一次权威重拉。
  // 只认 prev === 'closed': 首次 connecting→open 前面刚 load() 过, 再拉一次纯属浪费。
  watch(wsHandle.status, (s, prev) => {
    wsStatus.value = s
    if (s === 'open' && prev === 'closed') seatSync.reconcile('reconnect')
  })
})

onBeforeUnmount(() => {
  // 拖拽用的全局监听必须摘掉, 否则组件销毁后还会落座
  window.removeEventListener('pointerup', onDragEnd)
  window.removeEventListener('pointercancel', onDragCancel)
  wsHandle?.close()
  seatSync.dispose()
})

const remainingSeconds = computed(() => {
  if (!seatStore.map) return 0
  const left = dayjs(seatStore.map.startTime).diff(dayjs(), 'second')
  return left > 0 ? left : 0
})

const totalPrice = computed(() => {
  if (!seatStore.map) return 0
  return (seatStore.selected.size * seatStore.map.price).toFixed(2)
})

async function onConfirm() {
  const seats = [...seatStore.selected]
  if (seats.length === 0) {
    ElMessage.warning(t('seat.warnSelectSeat'))
    return
  }
  submitting.value = true
  // 先认领这些座位为「我的」: 自家锁座的 WS 事件可能早于 HTTP 响应到达,
  // 不先认领就会被当成「你已选的座位被他人锁定」(联调报告 F2)。
  // 认领**保留选座** —— 金额与数量不跳变, 失败时也不必恢复。
  seatStore.claimMyLocked(seats)
  try {
    const result = await lockSeats({ sessionId: sessionId.value, seatIndexes: seats.sort((a, b) => a - b) })
    ElMessage.success(t('seat.successLock'))
    seatStore.markMyLocked(seats)
    router.push({ name: 'payment', query: { orderNo: result.orderNo } })
  } catch (e: unknown) {
    // 锁座冲突靠「错误码 + 载荷」识别, 不靠中文消息串 ——
    // 后端改一次文案, 串匹配就静默失效(历史包袱, 归属见 api/errorCodes.ts)。
    const conflict = seatConflictOf(e)
    if (conflict === null) {
      // 不是锁座冲突 → 通用错误提示。
      // 撤回乐观认领: 锁座是全有或全无的, 失败时一个座位都没被锁上。
      // 随后的权威重拉(保留选座)把位图与订单状态对齐。
      seatStore.revokeMyLocked(seats)
      await refresh(true)
      const err = e as { message?: string }
      ElMessage.error(errorText(err?.message, 'seat.errorLockFailed'))
    } else if (conflict.length === 0) {
      // 是锁座冲突但没拿到座位列表 → 兜底: 全量 refresh
      seatStore.revokeMyLocked(seats)
      await refresh(true)
      ElMessage.error(t('seat.errorConflictRefreshed'))
    } else {
      // Phase D-⑱: 只对后端返回的 conflict 列表做局部置灰, 避免全量 refresh。
      // 先撤回乐观认领 —— 被抢的座位不是「我的锁的」, 得按「他人锁定」处理,
      // 否则它们不会闪烁、也不会从选座里剔出去。
      seatStore.revokeMyLocked(seats)
      seatStore.applyEvent('LOCKED', conflict)
      // P1-#6: 把被抢的座位转成"X排Y座"格式, 让用户一眼能定位
      const shown = formatSeatPositions(
        conflict,
        seatStore.map?.cols || 1,
        (row, col) => t('seat.rowColFormat', { row, col }),
        t('seat.listSeparator'),
      )
      ElMessage.error(t('seat.errorConflict', { list: shown }))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="seat-select" v-if="seatStore.map">
    <!-- P0-2: WS 连接状态条 — 断连/重连时给用户明确反馈, 避免静默失同步 -->
    <div class="ws-status" :class="`ws-${wsStatus}`" role="status" aria-live="polite">
      <span class="ws-dot"></span>
      <span class="ws-text">
        <template v-if="wsStatus === 'open'">{{ t('seat.wsOpen') }}</template>
        <template v-else-if="wsStatus === 'connecting'">{{ t('seat.wsConnecting') }}</template>
        <template v-else>{{ t('seat.wsClosed') }}</template>
      </span>
      <span v-if="seatSync.failed.value" class="ws-sync-failed">{{ t('seat.syncFailed') }}</span>
    </div>

    <!-- Header -->
    <div class="header-card">
      <div class="header-info">
        <h2 class="title">{{ seatStore.map.movieTitle }}</h2>
        <div class="meta">
          <span class="meta-item">{{ seatStore.map.hallName }}</span>
          <span class="meta-sep">·</span>
          <span class="meta-item">{{ dayjs(seatStore.map.startTime).format('MM-DD HH:mm') }}</span>
          <span class="meta-sep">·</span>
          <span class="meta-item price-tag">￥{{ seatStore.map.price.toFixed(2) }}{{ t('seat.pricePerSeat') }}</span>
        </div>
        <!-- 库存四段: 可选 / 我已锁 / 他人锁定 / 已售。
             可选段用选座域身份色 —— 座位图里「可选」本来就没有专属色(它是底色),
             所以这里用身份色不会和座位图里的任何一态撞。 -->
        <div class="inventory">
          <div class="inventory-bar">
            <span class="seg seg-available" :style="{ flexGrow: seatInventory.available }"></span>
            <span class="seg seg-mine" :style="{ flexGrow: seatInventory.mine }"></span>
            <span class="seg seg-locked" :style="{ flexGrow: seatInventory.locked }"></span>
            <span class="seg seg-sold" :style="{ flexGrow: seatInventory.sold }"></span>
          </div>
          <div class="inventory-legend">
            <span class="inv"><i class="inv-dot available"></i>{{ seatInventory.available }} {{ t('seat.legendAvailable') }}</span>
            <span class="inv"><i class="inv-dot mine"></i>{{ seatInventory.mine }} {{ t('seat.legendMine') }}</span>
            <span class="inv"><i class="inv-dot locked"></i>{{ seatInventory.locked }} {{ t('seat.legendLocked') }}</span>
            <span class="inv"><i class="inv-dot sold"></i>{{ seatInventory.sold }} {{ t('seat.legendSold') }}</span>
          </div>
        </div>
      </div>
      <el-button @click="refresh" :loading="refreshing" class="refresh-btn">
        <el-icon class="refresh-icon"><Refresh /></el-icon>
        {{ t('seat.refresh') }}
      </el-button>
    </div>

    <!-- 座位关注: 显式模式开关。关闭时下方座位交互与改动前逐字一致(零回归) -->
    <div class="watch-toolbar">
      <button
        type="button"
        class="watch-toggle"
        :class="{ active: seatStore.watchMode }"
        :aria-pressed="seatStore.watchMode"
        @click="seatStore.setWatchMode(!seatStore.watchMode)"
      >
        {{ t('seat.watchToggle') }}
        <span v-if="seatStore.watched.size" class="watch-count">{{ seatStore.watched.size }}</span>
      </button>
      <p v-if="seatStore.watchMode" class="watch-hint">{{ t('seat.watchHint', { n: MAX_WATCH }) }}</p>
    </div>

    <!-- 暗场票面: 放映厅平面图 (全站纸白, 唯独这里是一张深色票面)
         银幕 + 座位网格 + 图例同处一个暗场, 饱和状态色在深底上最醒目,
         5 种座位状态不会像纸面那样塌成一片灰。 -->
    <div class="hall-panel">
      <!-- Screen -->
      <div class="screen-wrapper">
        <div class="screen">
          <div class="screen-inner">{{ t('seat.screen') }}</div>
          <div class="screen-reflection"></div>
        </div>
        <div class="screen-stand"></div>
      </div>

      <!-- Seats Grid (Phase D-⑬: 抽 SeatItem 子组件, 父级只传 :index)
           行/列标签: 左侧行号 + 顶部列号, 统一挂在 seats-container 上设 --cols/--rows -->
      <div
        class="seats-container"
        :style="{ '--cols': seatStore.map.cols, '--rows': seatStore.map.rows }"
        @pointerleave="resetHoverPreview"
      >
        <div class="seats-grid" v-if="seatStore.map.cols <= 16">
          <!-- 列号表头: 占 1 格给左侧行号标签 -->
          <div class="corner-spacer"></div>
          <div v-for="c in seatStore.map.cols" :key="`c-${c}`" class="col-label">{{ c }}</div>
          <!-- 每一行: 行号 + cols 个座位 -->
          <template v-for="r in seatStore.map.rows" :key="`r-${r}`">
            <div class="row-label">{{ r }}</div>
            <template v-for="(_, idx) in seatStore.map.cols" :key="`c-${r}-${idx}`">
              <SeatItem
                :index="(r - 1) * seatStore.map.cols + idx"
                :preview="dragPreview.has((r - 1) * seatStore.map.cols + idx) || hoverPreview.has((r - 1) * seatStore.map.cols + idx)"
                @pointerdown="onSeatPointerDown"
                @pointerenter="onSeatPointerEnter"
              />
            </template>
          </template>
        </div>
        <!-- 超过 16 列: 退化为横向单行, 不显示行列标签 (大影厅) -->
        <div v-else>
          <!-- P2-#13: 大影厅布局丢了行列标签, 加个 hint 给用户交代 -->
          <div class="seats-hint">
            <el-icon class="hint-icon"><InfoFilled /></el-icon>
            {{ t('seat.largeHallHint', { rows: seatStore.map.rows, cols: seatStore.map.cols }) }}
          </div>
          <div class="seats" :style="{ '--cols': seatStore.map.cols }">
            <SeatItem
              v-for="i in seatStore.map.seatCount"
              :key="i - 1"
              :index="i - 1"
              :preview="dragPreview.has(i - 1) || hoverPreview.has(i - 1)"
              @pointerdown="onSeatPointerDown"
              @pointerenter="onSeatPointerEnter"
            />
          </div>
        </div>
      </div>

      <!-- Legend: 与座位共用同一组 CSS 变量, 改一处两边同时生效 -->
      <div class="legend">
        <div class="item"><span class="dot available" />{{ t('seat.legendAvailable') }}</div>
        <div class="item"><span class="dot selected" />{{ t('seat.legendSelected') }}</div>
        <div class="item"><span class="dot mine" />{{ t('seat.legendMine') }}</div>
        <div class="item"><span class="dot locked" />{{ t('seat.legendLocked') }}</div>
        <div class="item"><span class="dot sold" />{{ t('seat.legendSold') }}</div>
      </div>
    </div>

    <!-- Summary Card -->
    <div class="summary">
      <div class="summary-item">
        <div class="summary-content">
          <div class="summary-value">{{ seatStore.selected.size }} / {{ seatStore.maxSelect }}</div>
          <div class="summary-label">{{ t('seat.selectedCount') }}</div>
        </div>
      </div>
      <div class="summary-divider"></div>
      <div class="summary-item">
        <div class="summary-content">
          <div class="summary-value">{{ Math.floor(remainingSeconds / 3600) }}h {{ Math.floor((remainingSeconds % 3600) / 60) }}m</div>
          <div class="summary-label">{{ t('seat.countdown') }}</div>
        </div>
      </div>
      <div class="summary-divider"></div>
      <div class="summary-item highlight">
        <div class="summary-content">
          <div class="summary-value price">￥{{ totalPrice }}</div>
          <div class="summary-label">{{ t('seat.totalAmount') }}</div>
        </div>
      </div>
      <el-button type="primary" size="large" :loading="submitting" @click="onConfirm" class="confirm-btn">
        {{ t('seat.confirmLock') }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.seat-select {
  display: flex;
  flex-direction: column;
  gap: 20px;
  animation: fadeInUp 0.5s ease;
}

/* --- Header --- */
.header-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 28px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
}

.title {
  font-size: 20px;
  font-weight: 700;
  font-family: var(--font-display);
  color: var(--ink);
  margin-bottom: 8px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-item {
  color: var(--ink-3);
  font-size: 13px;
}

.meta-sep {
  color: var(--rule-strong);
}

/* 场次时间 = 票据数据, 走等宽 */
.meta-item.is-time {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  color: var(--ink-2);
}

.price-tag {
  color: var(--accent);
  font-weight: 600;
}

.refresh-btn {
  flex-shrink: 0;
}
.refresh-icon {
  margin-right: 4px;
}
.hint-icon {
  margin-right: 4px;
  vertical-align: -2px;
}

/* --- 库存四段条: 可选 / 我已锁 / 他人锁定 / 已售 ---
   宽度按数量成比例(flex-grow), 零数量自然塌成 0 宽。 */
.inventory {
  margin-top: 14px;
  max-width: 460px;
}
.inventory-bar {
  display: flex;
  height: 6px;
  border-radius: 3px;
  overflow: hidden;
  background: var(--paper-sunk);
}
.inventory-bar .seg {
  flex-basis: 0;
  min-width: 0;
}
.seg-available {
  background: var(--scene-seat);
}
.seg-mine {
  background: var(--ok);
}
.seg-locked {
  background: var(--warn);
}
.seg-sold {
  background: var(--danger);
}
.inventory-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 8px;
}
.inv {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--ink-3);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}
.inv-dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  flex-shrink: 0;
}
.inv-dot.available {
  background: var(--scene-seat);
}
.inv-dot.mine {
  background: var(--ok);
}
.inv-dot.locked {
  background: var(--warn);
}
.inv-dot.sold {
  background: var(--danger);
}

/* ============================================
   暗场票面: 放映厅平面图
   全站纸白, 唯独这张票面是深色的。要点是"框得住" ——
   发丝线边框 + 圆角 + 1px 阴影把它明确框成一张票,
   否则深色块在浅色页里会像嵌错了地方。
   ============================================ */
.hall-panel {
  background: var(--hall);
  border: 1px solid var(--hall-rule);
  border-radius: 6px;
  /* 顶部一道银幕反光: 内发光, 不外扩, 暗场因此"有光源" */
  box-shadow: var(--shadow-hall), inset 0 10px 20px -14px var(--hall-glow);
  padding: 20px 24px 8px;
  color: var(--hall-ink);
}

/* --- Screen --- */
.screen-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin: 4px 0 8px;
}

/* 银幕: 暗场内的浅色弧形, 不再用金渐变 + 40px 辉光 */
.screen {
  position: relative;
  width: 70%;
  min-width: 280px;
  height: 30px;
  border-radius: 50% 50% 0 0 / 100% 100% 0 0;
  background: var(--hall-wash);
  border: 1px solid var(--hall-rule);
  border-bottom: none;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 银幕自身的光: 选座域身份色的 8px 辉光(Flat-By-Default 的辉光上限) */
  box-shadow: 0 0 8px var(--hall-glow);
}

.screen-inner {
  font-family: var(--font-display);
  font-size: 12px;
  letter-spacing: 8px;
  color: var(--hall-ink-2);
  font-weight: 500;
}

/* 倒影: 淡到几乎看不见, 只留一点点"银幕在发光"的暗示 */
.screen-reflection {
  position: absolute;
  bottom: -14px;
  left: 50%;
  transform: translateX(-50%);
  width: 50%;
  height: 14px;
  /* 银幕光洒到座位区: 用选座域身份色, 不再是中性灰 */
  background: linear-gradient(180deg, var(--hall-glow), transparent);
  border-radius: 50%;
}

.screen-stand {
  width: 4px;
  height: 12px;
  background: var(--hall-rule);
  margin-top: 6px;
}

/* --- Seats Container --- */
.seats-container {
  overflow-x: auto;
  /* 触摸设备上, 拖拽选座不能带动页面滚动 —— 否则划座位会变成划页面 */
  touch-action: none;
}

/* 带行列标签的网格 (cols <= 16) */
.seats-grid {
  display: grid;
  grid-template-columns: 32px repeat(var(--cols), minmax(28px, 1fr));
  gap: 6px;
  min-width: max-content;
}
.corner-spacer {
  /* 左上角空白: 与列号行平齐 */
}
.col-label {
  text-align: center;
  font-size: 11px;
  color: var(--hall-ink-2);
  font-weight: 500;
  user-select: none;
  height: 16px;
  line-height: 16px;
  font-family: var(--font-mono);
}
.row-label {
  text-align: center;
  font-size: 11px;
  color: var(--hall-ink-2);
  font-weight: 500;
  user-select: none;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 行高与座位高一致 (座位 aspect-ratio: 1 + 字号 + padding 估算 ~38-44px) */
  height: 38px;
  font-family: var(--font-mono);
}

/* 大影厅退化为单行网格 */
.seats {
  display: grid;
  grid-template-columns: repeat(var(--cols), 1fr);
  gap: 6px;
}

/* P2-#13: 大影厅 hint */
.seats-hint {
  text-align: center;
  font-size: 12px;
  color: var(--hall-ink-2);
  margin-bottom: 12px;
  padding: 6px 12px;
  background: var(--hall-wash-soft);
  border-radius: var(--radius-sm);
  border: 1px dashed var(--hall-rule);
}

/* --- 拖拽连选的预览环 ---
   只画 outline, 不动座位底色与描边色:
   座位的「5 态去色后可分辨」契约锁的是底色, 加一圈框不改变它。
   ⚠️ 绝不能新增 --seat-* 变量 —— themeTokens.test.ts 断言那 13 个, 多一个就红。
      预览环直接引用选座域身份色。 */
.seat.drag-preview {
  outline: 2px solid var(--scene-seat);
  outline-offset: 1px;
}

/* ============================================
   座位 5 态 —— 颜色 + 非颜色 双重区分
   验收硬要求: 截图转灰度后 5 态仍必须可分辨。
   非颜色通道 = 描边粗细(已选) / 虚线(他人锁) / 45°斜纹(已售) /
                左侧亮条(我锁的) / 常规实心(可选)
   ⚠️ 图例 .legend .dot.* 与 .seat.* 共用下面这组 seat-* 变量,
      不要在图例里另写一套色值(原稿就是这么写的, 改座位忘改图例)。
   ============================================ */
.seat-select {
  --seat-avail-bg: #1e2a40;
  --seat-avail-border: var(--hall-rule);
  --seat-avail-ink: var(--hall-ink-2);
  --seat-sel-bg: var(--accent);
  --seat-sel-border: var(--accent-press);
  /* mine 三态刻意保留原值: 动它会把 5 态灰度间隔压回 1 级, 违反上方验收硬要求 */
  --seat-mine-bg: #2f6b4f;
  --seat-mine-border: #245239;
  --seat-mine-ink: #dcece2;
  --seat-locked-border: #3d4a63;
  --seat-locked-ink: #6b7a94;
  --seat-sold-bg: #151f33;
  --seat-sold-border: #232f47;
  --seat-sold-ink: #46536b;
}

.seat {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-family: var(--font-mono);
  background: var(--seat-avail-bg);
  border: 1px solid var(--seat-avail-border);
  border-radius: 4px 4px 8px 8px;
  cursor: pointer;
  user-select: none;
  transition: background-color var(--transition-fast), border-color var(--transition-fast),
    color var(--transition-fast);
  color: var(--seat-avail-ink);
  position: relative;
  min-width: 28px;
  min-height: 38px;
  /* P2-#14: 触摸优化 — 移动端 44x44 是 Apple HIG 推荐的最小点击区 */
  touch-action: manipulation;
}

/* 印刷风不做悬浮位移, 只提亮边框 + 文字 */
.seat:hover:not(.locked_other):not(.sold) {
  border-color: var(--accent);
  color: var(--hall-ink);
}

.seat.available {
  background: var(--seat-avail-bg);
  color: var(--seat-avail-ink);
  border-color: var(--seat-avail-border);
}

/* 已选: 实心主色 + 2px 粗描边 (粗细是非颜色通道) */
.seat.selected {
  background: var(--seat-sel-bg);
  border: 2px solid var(--seat-sel-border);
  color: var(--ink-inverse);
  font-weight: 600;
}

/* 我锁的: 实心墨绿 + 左侧 2px 亮条 */
.seat.mine {
  background: var(--seat-mine-bg);
  border-color: var(--seat-mine-border);
  color: var(--seat-mine-ink);
}
.seat.mine::before {
  content: "";
  position: absolute;
  left: 0;
  top: 3px;
  bottom: 3px;
  width: 2px;
  background: var(--hall-mine-bar);
  border-radius: 1px;
}

/* 他人锁: 虚线描边 + 透明底, 不再靠 opacity 压暗 */
.seat.locked_other {
  background: transparent;
  color: var(--seat-locked-ink);
  border: 1px dashed var(--seat-locked-border);
  cursor: not-allowed;
}

/* 已售: 45° 斜纹 + 极低对比文字 */
.seat.sold {
  background-color: var(--seat-sold-bg);
  background-image: repeating-linear-gradient(
    45deg,
    transparent 0 3px,
    var(--hall-stripe) 3px 4px
  );
  color: var(--seat-sold-ink);
  cursor: not-allowed;
  border-color: var(--seat-sold-border);
}

/* --- Legend --- */
.legend {
  display: flex;
  justify-content: center;
  gap: 20px;
  flex-wrap: wrap;
  padding: 12px 0 8px;
  margin-top: 8px;
  border-top: 1px solid var(--hall-rule);
}

.legend .item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--hall-ink-2);
}

.legend .dot {
  width: 18px;
  height: 18px;
  border-radius: 4px 4px 6px 6px;
  flex-shrink: 0;
}

/* 图例与座位引用同一组变量, 非颜色通道也同步 */
.legend .dot.available {
  background: var(--seat-avail-bg);
  border: 1px solid var(--seat-avail-border);
}
.legend .dot.selected {
  background: var(--seat-sel-bg);
  border: 2px solid var(--seat-sel-border);
}
.legend .dot.mine {
  background: var(--seat-mine-bg);
  border: 1px solid var(--seat-mine-border);
  position: relative;
}
.legend .dot.mine::before {
  content: "";
  position: absolute;
  left: 0;
  top: 3px;
  bottom: 3px;
  width: 2px;
  background: var(--hall-mine-bar);
}
.legend .dot.locked {
  background: transparent;
  border: 1px dashed var(--seat-locked-border);
}
.legend .dot.sold {
  background-color: var(--seat-sold-bg);
  background-image: repeating-linear-gradient(
    45deg,
    transparent 0 3px,
    var(--hall-stripe) 3px 4px
  );
  border: 1px solid var(--seat-sold-border);
}

/* --- Summary Card --- */
.summary {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 20px 28px;
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-paper);
  flex-wrap: wrap;
}

.summary-item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.summary-item.highlight {
  flex: 1;
}

/* 计数 / 倒计时 / 金额都是票据数据 → 等宽 */
.summary-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

.summary-label {
  font-size: 12px;
  color: var(--ink-3);
}

.summary-value.price {
  font-family: var(--font-mono);
  font-size: 28px;
  color: var(--accent);
}

.summary-divider {
  width: 1px;
  height: 32px;
  background: var(--rule);
}

.confirm-btn {
  flex-shrink: 0;
}

/* --- Responsive --- */
@media (max-width: 768px) {
  .header-card {
    padding: 16px 20px;
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  /* 暗场票面的内边距随屏幕收窄 */
  .hall-panel {
    padding: 16px 14px 4px;
  }
  /* P2-#14: 移动端座位字号从 10px → 13px, 配合更大的最小尺寸, 触摸更准 */
  .seat {
    font-size: 13px;
    min-width: 32px;
    min-height: 32px;
  }
  .col-label,
  .row-label {
    font-size: 12px;
  }
  .summary {
    padding: 16px 20px;
    gap: 16px;
  }
  .summary-divider {
    display: none;
  }
  .summary-item {
    flex: 1;
  }
  .confirm-btn {
    width: 100%;
  }
}

@media (max-width: 480px) {
  .summary-item {
    flex: 0 0 100%;
    justify-content: flex-start;
  }
  .summary-value.price {
    font-size: 22px;
  }
}

/* --- P0-2: WS 连接状态条 --- */
.ws-status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 500;
  border: 1px solid;
  animation: fadeInUp 0.3s ease;
}
.ws-status .ws-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.ws-status.ws-open {
  background: rgb(var(--ok-rgb) / 0.08);
  border-color: rgb(var(--ok-rgb) / 0.3);
  color: var(--ok);
}
.ws-status.ws-open .ws-dot {
  background: var(--ok);
  box-shadow: 0 0 0 0 rgb(var(--ok-rgb) / 0.5);
  animation: ws-pulse 2s ease-in-out infinite;
}
.ws-status.ws-connecting {
  background: rgb(var(--warn-rgb) / 0.08);
  border-color: rgb(var(--warn-rgb) / 0.3);
  color: var(--warn);
}
.ws-status.ws-connecting .ws-dot {
  background: var(--warn);
  animation: ws-blink 1s ease-in-out infinite;
}
.ws-status.ws-closed {
  background: rgb(var(--danger-rgb) / 0.08);
  border-color: rgb(var(--danger-rgb) / 0.3);
  color: var(--danger);
}
.ws-status.ws-closed .ws-dot {
  background: var(--danger);
  animation: ws-blink 0.6s ease-in-out infinite;
}
@keyframes ws-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgb(var(--ok-rgb) / 0.5); }
  50% { box-shadow: 0 0 0 6px rgb(var(--ok-rgb) / 0); }
}
@keyframes ws-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

/* --- P0-2: 被他人抢走的已选座位 — 1.8s 红色脉冲外环, 强提示 --- */
.seat.flash {
  animation: seat-conflict 0.4s ease-in-out 4;
  position: relative;
  z-index: 2;
}
.seat.flash::before {
  content: '';
  position: absolute;
  inset: -4px;
  border: 2px solid var(--danger);
  border-radius: 6px 6px 10px 10px;
  pointer-events: none;
  animation: seat-conflict-ring 0.4s ease-in-out 4;
}
@keyframes seat-conflict {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.18); }
}
@keyframes seat-conflict-ring {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgb(var(--danger-rgb) / 0.55); }
  50% { opacity: 0.5; box-shadow: 0 0 0 6px rgb(var(--danger-rgb) / 0); }
}

/* ============================================
   座位关注(Seat Watch)
   ============================================ */
.watch-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.watch-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-2);
  background: var(--paper-raised);
  border: 1px solid var(--rule);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: color var(--transition-fast), border-color var(--transition-fast);
}
.watch-toggle:hover {
  color: var(--ink);
  border-color: var(--rule-strong);
}
.watch-toggle.active {
  color: var(--accent);
  border-color: var(--accent);
}
.watch-count {
  min-width: 18px;
  padding: 0 5px;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  color: var(--accent);
  background: rgb(var(--accent-rgb) / 0.12);
  border-radius: 9px;
}
.watch-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-3);
}

/* 关注标识: 只用角标字形, 不改背景色 / 边框色。
   座位图有「5 态去色后仍可分辨」的既有契约(themeTokens.test.ts 固化为可执行断言),
   引入第 6 种座位背景色会与既有 5 态争抢明度层级, 把间隔压回 1 级。
   非颜色通道 = 角标的有无本身。 */
.seat.watched::after {
  content: "";
  position: absolute;
  top: 3px;
  right: 3px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--accent);
  /* 描一圈暗场色, 保证在「已售」的 45 度斜纹底上也读得出来 */
  box-shadow: 0 0 0 1px var(--hall);
  pointer-events: none;
}

/* 权威重拉连续失败后的提示 */
.ws-sync-failed {
  margin-left: auto;
  padding-left: 10px;
  color: var(--danger);
  font-weight: 600;
}
</style>
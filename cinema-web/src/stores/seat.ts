import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { decodeBitmap } from '../utils/bitmap'
import type { SeatMap } from '../api/seat'
import { computeApplyEvent, computeApplyPreselect, filterBySeq } from './seatEventReducer'
import { computeRevalidateSelection, computeToggleWatch, removeWatched, type WatchToggleReason } from './seatWatch'

/** 座位关注容量上限(纯客户端, 不落库、不跨设备同步、刷新即失) */
export const MAX_WATCH = 8

/**
 * 座位图 store (Phase D-⑬ 优化)
 * <ul>
 *   <li>myLockedSeats 改 Set<number>, statusAt 改 O(1) 查找</li>
 *   <li>rowOfIdx / colOfIdx 预生成, rowCol 改 O(1) 数组访问</li>
 *   <li>map / lockBits / soldBits 仍为 ref, 保持响应式, 但变更时整块替换触发最小更新</li>
 * </ul>
 */
export const useSeatStore = defineStore('seat', () => {
  const map = ref<SeatMap | null>(null)
  const lockBits = ref<Uint8Array>(new Uint8Array())
  const soldBits = ref<Uint8Array>(new Uint8Array())
  const myLockedSet = ref<Set<number>>(new Set())    // Phase D-⑬: O(1) 查找
  const rowOfIdx = ref<number[]>([])                  // Phase D-⑬: 预生成
  const colOfIdx = ref<number[]>([])
  const selected = ref<Set<number>>(new Set())
  const maxSelect = 4
  // P0-2: 刚被他人抢走的座位(原在 selected 中) — 用于驱动 UI 闪烁/提示
  // 存的是 index, 1.8s 后自动清除
  const conflictFlash = ref<Set<number>>(new Set())
  // 按座位索引寻址的「已应用事件序号」, 长度 = seatCount。用于丢弃乱序到达的迟到事件。
  // 之所以按座位而不是整场次一个值: 序号只在同一座位上提供先后关系(并发请求谁先 PUBLISH
  // 不确定), 用整场次单值会误杀跨座位的慢事件, 导致那个座位的状态变更彻底丢失。
  const lastSeq = ref<number[]>([])
  // ---- 座位关注(Seat Watch) ----
  // 显式模式开关: 关闭时不可选座位的点击 / 键盘可达性 / 提示文案与改动前完全一致(零回归)。
  const watchMode = ref(false)
  // 被关注的座位索引。纯客户端意图表达 —— 不占位、不建单、不落库、刷新即清。
  const watched = ref<Set<number>>(new Set())

  /** 1.8s 后清掉闪烁座位, 让高亮恢复普通样式。三处复用, 避免各写一份。 */
  function scheduleFlashClear(flashed: Iterable<number>) {
    const set = new Set(flashed)
    window.setTimeout(() => {
      const cur = new Set(conflictFlash.value)
      let changed = false
      for (const idx of set) {
        if (cur.delete(idx)) changed = true
      }
      if (changed) conflictFlash.value = cur
    }, 1800)
  }

  /**
   * 装载座位图。
   *
   * @param opts.preserveSelection 权威重拉时为 true —— 不直接丢弃用户已选座位, 而是逐个用
   *   新位图复核: 变为已售或他人锁定的移出并加入冲突闪烁。缺省为 false, 保持改动前的语义
   *   (手动刷新按钮 / 首次装载仍清空选座)。
   * @returns 本次被剔除的已选座位索引; 非保留模式下恒为空数组
   */
  function load(m: SeatMap, opts?: { preserveSelection?: boolean }): number[] {
    const prevSessionId = map.value?.sessionId
    map.value = m
    lockBits.value = decodeBitmap(m.lockBitmap, m.seatCount)
    soldBits.value = decodeBitmap(m.soldBitmap, m.seatCount)
    // 预生成 O(1) 查表
    myLockedSet.value = new Set(m.myLockedSeats ?? [])
    const cols = m.cols || 1
    const row = new Array<number>(m.seatCount)
    const col = new Array<number>(m.seatCount)
    for (let i = 0; i < m.seatCount; i++) {
      row[i] = Math.floor(i / cols) + 1
      col[i] = (i % cols) + 1
    }
    rowOfIdx.value = row
    colOfIdx.value = col
    // 换场次才重置序号; 同场次重拉必须**保留**已有值 ——
    // 重置会让重拉前仍在途的旧事件(seq 小于已应用值)被重新应用,
    // 把刚刷新的权威位图改回旧状态。
    if (prevSessionId !== m.sessionId || lastSeq.value.length !== m.seatCount) {
      lastSeq.value = new Array<number>(m.seatCount).fill(0)
    }
    // 换场次: 关注集合随页面语义一并作废(关注只对当前场次成立)
    if (prevSessionId !== m.sessionId) {
      watched.value = new Set()
      watchMode.value = false
    }

    if (!opts?.preserveSelection) {
      selected.value = new Set()
      return []
    }

    const r = computeRevalidateSelection(
      selected.value, conflictFlash.value,
      lockBits.value, soldBits.value, myLockedSet.value,
    )
    selected.value = r.selected
    if (r.hasConflict) {
      conflictFlash.value = r.conflictFlash
      scheduleFlashClear(r.lost)
    }
    return r.lost
  }

  // 兼容老接口: 把 Set 转回数组, 保持 SeatMap 类型契约
  const myLockedSeats = computed(() => Array.from(myLockedSet.value))

  function statusAt(idx: number): 'AVAILABLE' | 'SOLD' | 'LOCKED_OTHER' | 'LOCKED_MINE' | 'SELECTED' {
    if (!map.value) return 'AVAILABLE'
    if (soldBits.value[idx]) return 'SOLD'
    if (lockBits.value[idx]) {
      return myLockedSet.value.has(idx) ? 'LOCKED_MINE' : 'LOCKED_OTHER'   // O(1)
    }
    return selected.value.has(idx) ? 'SELECTED' : 'AVAILABLE'
  }

  function rowCol(idx: number): { row: number; col: number } {
    return { row: rowOfIdx.value[idx] ?? 0, col: colOfIdx.value[idx] ?? 0 }   // O(1)
  }

  function toggle(idx: number): boolean {
    if (!map.value) return false
    const cur = statusAt(idx)
    if (cur !== 'AVAILABLE' && cur !== 'SELECTED') return false
    if (selected.value.has(idx)) {
      selected.value.delete(idx)
    } else {
      if (selected.value.size >= maxSelect) return false
      selected.value.add(idx)
    }
    return true
  }

  function clearSelection() {
    selected.value = new Set()
  }

  /**
   * T7: 应用 preselect(来自 chat 行动卡片 — spec §7.2)。
   *
   * <p>追加语义 + 超 maxSelect 按 preselect 索引最小优先剔除 + 冲突闪烁
   * (1.8s 自动清除, 与 applyEvent 走同一 schedule)。
   *
   * <p>调用时机: SeatSelect.vue onMounted 后, refresh() 完成拉到位图后再调。
   * (不能在 refresh() 之前,因为位图未知,冲突判断失效)。
   */
  function applyPreselect(preselect: number[]) {
    if (!map.value || preselect.length === 0) return
    const r = computeApplyPreselect(
      selected.value,
      conflictFlash.value,
      preselect,
      lockBits.value,
      soldBits.value,
      myLockedSet.value,
      map.value.seatCount,
      maxSelect,
    )
    selected.value = r.newSelected
    if (r.hasNewFlash) {
      conflictFlash.value = r.newConflictFlash
      scheduleFlashClear(r.newConflictFlash)
    }
  }

  /** WS 事件应用: 本地位图同步. 注意: ref 包裹的 Uint8Array 必须整体替换才能触发响应式。 */
  function applyEvent(type: string, seats: number[], seq?: number) {
    if (seats.length === 0) return
    // 先按座位序号过滤乱序的迟到事件(无序号时全部接受, 退化成改动前的纯增量行为)
    const f = filterBySeq(lastSeq.value, seats, seq)
    lastSeq.value = f.nextLastSeq
    if (f.accepted.length === 0) return   // 整条都是迟到事件, 位图不动
    // 释放 = 座位回到可选池, 我对它的「我的锁」随之失效(否则它被别人锁上时会被当成我的)
    if (type === 'RELEASED') {
      const nextMy = new Set(myLockedSet.value)
      let changed = false
      for (const s of f.accepted) {
        if (nextMy.delete(s)) changed = true
      }
      if (changed) {
        myLockedSet.value = nextMy
        if (map.value) map.value = { ...map.value, myLockedSeats: Array.from(nextMy) }
      }
    }
    // 位图 + selected/conflictFlash 状态机抽出到 computeApplyEvent (纯函数, 易测)
    // myLockedSet 传进去: 我自己锁的座位被 LOCKED/SOLD 时不算冲突(联调报告 F2)
    const r = computeApplyEvent(
      lockBits.value, soldBits.value,
      selected.value, conflictFlash.value,
      type, f.accepted,
      myLockedSet.value,
    )
    lockBits.value = r.newLockBits
    soldBits.value = r.newSoldBits
    selected.value = r.newSelected
    if (r.hasConflict) {
      conflictFlash.value = r.newConflictFlash
      // 1.8s 后清掉, 让闪烁/高亮恢复普通 LOCKED_OTHER 样式
      scheduleFlashClear(r.newConflictFlash)
    }
  }

  // ============ 座位关注(Seat Watch)============

  function setWatchMode(on: boolean) {
    watchMode.value = on
    if (!on) watched.value = new Set()   // 退出模式即清空关注, 避免下次进入时冒出陈旧关注
  }

  /** 切换某座位的关注; 返回结果分支供 UI 决定提示文案 */
  function toggleWatch(idx: number): WatchToggleReason {
    const r = computeToggleWatch(watched.value, idx, MAX_WATCH, statusAt(idx))
    watched.value = r.watched
    return r.reason
  }

  /** 释放提醒命中后结束这些座位的一次性关注 */
  function clearWatchHits(seats: number[]) {
    watched.value = removeWatched(watched.value, seats)
  }

  function clearWatch() {
    watched.value = new Set()
  }

  /**
   * 认领座位为「我的」: 置位图锁定位 + 记进 myLockedSet, **保留当前选座**。
   *
   * <p>锁座请求发出前调用 —— 自家锁座的 WS 事件可能在 HTTP 响应之前到达,
   * 那时客户端还不知道这些座位是「我的锁的」, 于是会被当成「被他人抢走」
   * (联调报告 F2: 锁座成功后弹「你已选的 2 个座位被他人锁定」)。
   * 先认领, 事件到达时就能区分出「这是我自己锁的」。
   */
  function claimMyLocked(seats: number[]) {
    if (seats.length === 0) return
    const newLockBits = new Uint8Array(lockBits.value)
    for (const s of seats) {
      if (s >= 0 && s < newLockBits.length) newLockBits[s] = 1
    }
    lockBits.value = newLockBits
    const newSet = new Set(myLockedSet.value)
    for (const s of seats) newSet.add(s)
    myLockedSet.value = newSet
    if (map.value) {
      map.value = { ...map.value, myLockedSeats: Array.from(newSet) }
    }
  }

  /** 锁座成功后: 立即把锁位的位图置 1(乐观更新, 等待 WS 兜底) */
  function markMyLocked(seats: number[]) {
    if (seats.length === 0) return
    claimMyLocked(seats)
    // 座位已从「待锁」变成「已锁」, 不再留在选座里
    clearSelection()
  }

  /**
   * 锁座失败后撤销认领: 撤回「我的锁」标记与乐观置上的位图锁定位。
   *
   * <p>锁座是**全有或全无**的(lock_seat.lua 先查冲突, 任一冲突则整体失败,
   * 一个座位都不锁), 所以失败时这些座位并没有被任何人锁上 —— 乐观置的位必须撤掉,
   * 否则它们会被误判成「已锁」而选不了。权威状态由随后的 seat-map 重拉纠正。
   */
  function revokeMyLocked(seats: number[]) {
    if (seats.length === 0) return
    const newSet = new Set(myLockedSet.value)
    for (const s of seats) newSet.delete(s)
    myLockedSet.value = newSet
    const newLockBits = new Uint8Array(lockBits.value)
    for (const s of seats) {
      if (s >= 0 && s < newLockBits.length) newLockBits[s] = 0
    }
    lockBits.value = newLockBits
    if (map.value) {
      map.value = { ...map.value, myLockedSeats: Array.from(newSet) }
    }
  }

  return {
    map, lockBits, soldBits, selected, maxSelect,
    myLockedSeats,                                  // computed: 兼容原组件读取
    conflictFlash,                                  // P0-2: 暴露给 SeatItem
    lastSeq,                                        // 按座位的已应用事件序号
    watchMode, watched,                             // 座位关注
    load, statusAt, rowCol, toggle, clearSelection, applyEvent, applyPreselect, markMyLocked,
    claimMyLocked, revokeMyLocked,
    setWatchMode, toggleWatch, clearWatchHits, clearWatch,
  }
})

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { decodeBitmap } from '../utils/bitmap'
import type { SeatMap } from '../api/seat'
import { computeApplyEvent, computeApplyPreselect } from './seatEventReducer'

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

  function load(m: SeatMap) {
    map.value = m
    lockBits.value = decodeBitmap(m.lockBitmap, m.seatCount)
    soldBits.value = decodeBitmap(m.soldBitmap, m.seatCount)
    selected.value = new Set()
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
   * T7: 应用 preselect(来自 chat 行动卡片 — spec §7.2).
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
      const flashed = new Set(r.newConflictFlash)
      window.setTimeout(() => {
        const cur = new Set(conflictFlash.value)
        let changed = false
        for (const idx of flashed) {
          if (cur.delete(idx)) changed = true
        }
        if (changed) conflictFlash.value = cur
      }, 1800)
    }
  }

  /** WS 事件应用: 本地位图同步. 注意: ref 包裹的 Uint8Array 必须整体替换才能触发响应式. */
  function applyEvent(type: string, seats: number[]) {
    if (seats.length === 0) return
    // 位图 + selected/conflictFlash 状态机抽出到 computeApplyEvent (纯函数, 易测)
    const r = computeApplyEvent(
      lockBits.value, soldBits.value,
      selected.value, conflictFlash.value,
      type, seats,
    )
    lockBits.value = r.newLockBits
    soldBits.value = r.newSoldBits
    selected.value = r.newSelected
    if (r.hasConflict) {
      conflictFlash.value = r.newConflictFlash
      // 1.8s 后清掉, 让闪烁/高亮恢复普通 LOCKED_OTHER 样式
      const flashed = r.newConflictFlash
      window.setTimeout(() => {
        const cur = new Set(conflictFlash.value)
        let changed = false
        for (const idx of flashed) {
          if (cur.delete(idx)) changed = true
        }
        if (changed) conflictFlash.value = cur
      }, 1800)
    }
  }

  /** 锁座成功后: 立即把锁位的位图置 1(乐观更新, 等待 WS 兜底) */
  function markMyLocked(seats: number[]) {
    if (seats.length === 0) return
    const newLockBits = new Uint8Array(lockBits.value)
    for (const s of seats) {
      if (s >= 0 && s < newLockBits.length) newLockBits[s] = 1
    }
    lockBits.value = newLockBits
    // 同步更新 Set
    const newSet = new Set(myLockedSet.value)
    for (const s of seats) newSet.add(s)
    myLockedSet.value = newSet
    if (map.value) {
      map.value = { ...map.value, myLockedSeats: Array.from(newSet) }
      clearSelection()
    }
  }

  return {
    map, lockBits, soldBits, selected, maxSelect,
    myLockedSeats,                                  // computed: 兼容原组件读取
    conflictFlash,                                  // P0-2: 暴露给 SeatItem
    load, statusAt, rowCol, toggle, clearSelection, applyEvent, applyPreselect, markMyLocked,
  }
})

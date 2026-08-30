import { defineStore } from 'pinia'
import { ref } from 'vue'
import { decodeBitmap } from '../utils/bitmap'
import type { SeatMap } from '../api/seat'

export const useSeatStore = defineStore('seat', () => {
  const map = ref<SeatMap | null>(null)
  const lockBits = ref<Uint8Array>(new Uint8Array())
  const soldBits = ref<Uint8Array>(new Uint8Array())
  const selected = ref<Set<number>>(new Set())
  const maxSelect = 4

  function load(m: SeatMap) {
    map.value = m
    lockBits.value = decodeBitmap(m.lockBitmap, m.seatCount)
    soldBits.value = decodeBitmap(m.soldBitmap, m.seatCount)
    selected.value = new Set()
  }

  function statusAt(idx: number): 'AVAILABLE' | 'SOLD' | 'LOCKED_OTHER' | 'LOCKED_MINE' | 'SELECTED' {
    if (!map.value) return 'AVAILABLE'
    if (soldBits.value[idx]) return 'SOLD'
    if (lockBits.value[idx]) {
      return map.value.myLockedSeats?.includes(idx) ? 'LOCKED_MINE' : 'LOCKED_OTHER'
    }
    return selected.value.has(idx) ? 'SELECTED' : 'AVAILABLE'
  }

  function rowCol(idx: number): { row: number; col: number } {
    const cols = map.value?.cols || 1
    return { row: Math.floor(idx / cols) + 1, col: (idx % cols) + 1 }
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

  /** WS 事件应用: 本地位图同步. 注意: ref 包裹的 Uint8Array 必须整体替换才能触发响应式. */
  function applyEvent(type: string, seats: number[]) {
    if (seats.length === 0) return
    const newLockBits = new Uint8Array(lockBits.value)
    const newSoldBits = new Uint8Array(soldBits.value)
    for (const s of seats) {
      if (s < 0 || s >= newLockBits.length) continue
      if (type === 'LOCKED') {
        newLockBits[s] = 1
        if (selected.value.has(s)) selected.value.delete(s)
      } else if (type === 'RELEASED') {
        newLockBits[s] = 0
      } else if (type === 'SOLD') {
        newLockBits[s] = 1
        newSoldBits[s] = 1
        if (selected.value.has(s)) selected.value.delete(s)
      }
    }
    lockBits.value = newLockBits
    soldBits.value = newSoldBits
  }

  /** 锁座成功后: 立即把锁位的位图置 1(乐观更新, 等待 WS 兜底) */
  function markMyLocked(seats: number[]) {
    if (seats.length === 0) return
    const newLockBits = new Uint8Array(lockBits.value)
    for (const s of seats) {
      if (s >= 0 && s < newLockBits.length) newLockBits[s] = 1
    }
    lockBits.value = newLockBits
    if (map.value) {
      map.value = { ...map.value, myLockedSeats: [...seats] }
      clearSelection()
    }
  }

  return {
    map, lockBits, soldBits, selected, maxSelect,
    load, statusAt, rowCol, toggle, clearSelection, applyEvent, markMyLocked,
  }
})
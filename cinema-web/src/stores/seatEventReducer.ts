/**
 * seat store applyEvent 的纯函数 seam.
 *
 * <p>#6 收尾: 把位图同步 + selected/conflictFlash 状态机的逻辑抽出,
 * 让 unit test 无需 mock Vue refs / Pinia 就能锁定 reducer 语义.
 *
 * <p>1.8s 超时清理的 setTimeout 留在 store wrapper 里(纯函数无法表达时间),
 * 这里只返回 hasConflict 让 wrapper 决定是否需要排程超时.
 */

/**
 * @param prevLockBits  修改前的 lock bitmap
 * @param prevSoldBits 修改前的 sold bitmap
 * @param prevSelected 修改前用户已选的座位 Set
 * @param prevConflictFlash 修改前闪烁高亮的座位 Set
 * @param type        WS 事件类型: 'LOCKED' | 'RELEASED' | 'SOLD' | 其它
 * @param seats       受影响的座位索引数组
 * @returns 修改后的新位图 + 新 selected/conflictFlash + hasConflict 标志
 */
export function computeApplyEvent(
  prevLockBits: Uint8Array,
  prevSoldBits: Uint8Array,
  prevSelected: Set<number>,
  prevConflictFlash: Set<number>,
  type: string,
  seats: number[],
): {
  newLockBits: Uint8Array
  newSoldBits: Uint8Array
  newSelected: Set<number>
  newConflictFlash: Set<number>
  hasConflict: boolean
} {
  if (seats.length === 0) {
    return {
      newLockBits: new Uint8Array(prevLockBits),
      newSoldBits: new Uint8Array(prevSoldBits),
      newSelected: new Set(prevSelected),
      newConflictFlash: new Set(prevConflictFlash),
      hasConflict: false,
    }
  }

  const newLockBits = new Uint8Array(prevLockBits)
  const newSoldBits = new Uint8Array(prevSoldBits)
  const newSelected = new Set(prevSelected)
  const newConflictFlash = new Set(prevConflictFlash)
  let hasConflict = false

  for (const s of seats) {
    if (s < 0 || s >= newLockBits.length) continue
    if (type === 'LOCKED') {
      newLockBits[s] = 1
      if (newSelected.has(s)) {
        newSelected.delete(s)
        newConflictFlash.add(s)
        hasConflict = true
      }
    } else if (type === 'RELEASED') {
      newLockBits[s] = 0
    } else if (type === 'SOLD') {
      newLockBits[s] = 1
      newSoldBits[s] = 1
      if (newSelected.has(s)) {
        newSelected.delete(s)
        newConflictFlash.add(s)
        hasConflict = true
      }
    }
  }

  return { newLockBits, newSoldBits, newSelected, newConflictFlash, hasConflict }
}
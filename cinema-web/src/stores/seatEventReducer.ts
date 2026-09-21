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

// ============ T7: preselect 处理(对话式订票助手行动卡片)============

/**
 * 把 route.query.preselect(逗号分隔字符串)解析为座位索引数组.
 *
 * <p>来源:ActionCard.vue 的 onClick 跳 /seat/:sessionId?preselect=52,53,54
 * (spec §7.2 + actionCardRoute.ts buildSeatRoute).
 *
 * <p>解析规则:
 * <ul>
 *   <li>逗号分隔(`,`)</li>
 *   <li>每个段 trim 后用 parseInt(10) 解析</li>
 *   <li>非数字段(如空字符串、字母)跳过(避免 NaN 进 selected)</li>
 *   <li>负数 / NaN / 非法值跳过</li>
 * </ul>
 *
 * <p>独立 export 是为了单元测试,避免 SeatSelect.vue mount 开销。
 */
export function parsePreselectFromQuery(queryValue: unknown): number[] {
  if (typeof queryValue !== 'string' || queryValue.length === 0) return []
  return queryValue
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
    .map((s) => parseInt(s, 10))
    .filter((n) => Number.isFinite(n) && n >= 0)
}

/**
 * 计算 preselect 应用结果(纯函数 seam).
 *
 * <p>spec §7.2 行为:
 * <ul>
 *   <li><b>追加语义</b> — 既有的 {@code selected} 不被覆盖,只有新增</li>
 *   <li>已被他人锁/售的座位入 {@code conflictFlash} 触发红色脉冲(1.8s 自动清,
 *       由 store wrapper 排程)</li>
 *   <li>既有的 selected 已 ≥ maxSelect → preselect 全部跳过(没有空间)</li>
 *   <li>合并后 > maxSelect → 按 preselect 索引<b>最小优先剔除</b>直到 maxSelect 个</li>
 * </ul>
 */
export function computeApplyPreselect(
  prevSelected: Set<number>,
  prevConflictFlash: Set<number>,
  preselect: number[],
  lockBits: Uint8Array,
  soldBits: Uint8Array,
  myLockedSet: Set<number>,
  seatCount: number,
  maxSelect: number,
): {
  newSelected: Set<number>
  newConflictFlash: Set<number>
  hasNewFlash: boolean
} {
  const newSelected = new Set(prevSelected)
  const newConflictFlash = new Set(prevConflictFlash)
  let hasNewFlash = false

  // 1. 既有的 selected 已满 → preselect 全跳过(无空间)
  if (prevSelected.size >= maxSelect) {
    return { newSelected, newConflictFlash, hasNewFlash }
  }

  // 2. 收集 preselect 中"有效可加"的(AVAILABLE, 不在 selected/锁/售中)
  const validPreselect: number[] = []
  for (const idx of preselect) {
    if (!Number.isFinite(idx) || idx < 0 || idx >= seatCount) continue
    if (soldBits[idx]) {
      // 已售 → 闪烁
      if (!newConflictFlash.has(idx)) hasNewFlash = true
      newConflictFlash.add(idx)
      continue
    }
    if (lockBits[idx]) {
      // 已锁(LOCKED_OTHER 我不锁的)— 闪烁;LOCKED_MINE(我已锁)跳过
      if (!myLockedSet.has(idx)) {
        if (!newConflictFlash.has(idx)) hasNewFlash = true
        newConflictFlash.add(idx)
      }
      continue
    }
    // AVAILABLE 且不在 selected(否则 set.add 是 no-op)
    if (newSelected.has(idx)) continue
    validPreselect.push(idx)
  }

  // 3. 合并
  for (const idx of validPreselect) newSelected.add(idx)

  // 4. 超 maxSelect → 按 preselect 索引最小优先剔除
  if (newSelected.size > maxSelect) {
    const sorted = [...validPreselect].sort((a, b) => a - b)
    let overflow = newSelected.size - maxSelect
    for (const idx of sorted) {
      if (overflow <= 0) break
      if (newSelected.delete(idx)) overflow--
    }
  }

  return { newSelected, newConflictFlash, hasNewFlash }
}
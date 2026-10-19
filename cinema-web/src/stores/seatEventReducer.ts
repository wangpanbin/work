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
      // 释放事件同时清锁定位与已售位:
      //   取消 / 超时关单 → release_seat.lua 只清 lock, sold 本就为 0, 这里是幂等空操作
      //   退票             → refund_seat.lua 同清 sold + lock, 这里必须清, 否则残留"已售"
      // 服务端不变式: 一个座位要么处于待支付锁、要么已售, 不会同时, 所以无条件清零既正确又幂等。
      newLockBits[s] = 0
      newSoldBits[s] = 0
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

// ============ 事件序号: 按座位过滤乱序到达的迟到事件 ============

/**
 * 用**按座位寻址**的已应用序号数组过滤 WS 事件。
 *
 * <p><b>为什么必须按座位, 不能用一个整场次的序号</b>: 两个并发请求各自取到 seq=5 与 seq=6,
 * 谁先 PUBLISH 是不确定的, 客户端确实可能先收到 6 再收到 5。序号因此只能在
 * <b>同一座位上</b>提供先后关系, 提供不了全序。
 * 若用一个整场次的 lastSeq, 当 5 号与 6 号事件作用于<b>不同座位</b>时, 迟到的 5 号会被
 * 判为陈旧而整条丢弃 —— 那个座位的状态变更就彻底丢失, 直到下次权威重拉才恢复。
 *
 * <p><b>无序号的处理</b>: `seq` 缺失、类型非法、或 `seq <= 0`(服务端自增失败的兜底值)时,
 * 一律「总是应用」且**不更新** lastSeq。这里 `seq <= 0` 尤其关键: 若把它当普通序号参与
 * 比较, `0 > lastSeq[s]` 恒为假, 会把所有事件静默丢弃 —— 一个不报错的实时系统失效。
 *
 * <p><b>越界座位索引</b>直接跳过(既不接受也不丢弃), 与 {@link computeApplyEvent} 的边界一致。
 *
 * @param lastSeq  按座位索引寻址的已应用序号, 长度 = seatCount
 * @param seats    受影响的座位索引
 * @param seq      事件序号; undefined / 非有限 / <=0 表示「无序号」
 */
export function filterBySeq(
  lastSeq: ArrayLike<number>,
  seats: number[],
  seq: number | undefined,
): {
  accepted: number[]
  dropped: number[]
  nextLastSeq: number[]
} {
  const next = Array.from(lastSeq as ArrayLike<number>)

  // lastSeq 尚未按 seatCount 初始化(首次装载前) → 没有可比较的按座位序号, 全部接受。
  // 不加这条守卫的话, 下面 `s >= next.length` 会把所有座位判成越界而静默丢弃,
  // 表现是「连上 WebSocket 但座位图再也不更新」—— 一个不报错的实时失效。
  if (next.length === 0) {
    return { accepted: [...seats], dropped: [], nextLastSeq: next }
  }

  // 无序号 → 总是应用, 且不推进 lastSeq
  if (typeof seq !== 'number' || !Number.isFinite(seq) || seq <= 0) {
    return { accepted: [...seats], dropped: [], nextLastSeq: next }
  }

  const accepted: number[] = []
  const dropped: number[] = []
  for (const s of seats) {
    if (!Number.isInteger(s) || s < 0 || s >= next.length) continue
    if (seq > next[s]) {
      next[s] = seq
      accepted.push(s)
    } else {
      dropped.push(s)
    }
  }
  return { accepted, dropped, nextLastSeq: next }
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
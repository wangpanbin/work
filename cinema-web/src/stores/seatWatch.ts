/**
 * 座位关注(Seat Watch) 与权威重拉的纯函数接缝.
 *
 * <p>本仓 vitest 跑在 `environment: 'node'`, 没装 jsdom / @vue/test-utils, **挂不了 SFC**。
 * 因此所有新增判定逻辑都下沉到这里(无副作用纯函数), 由 store / 组件只负责接线与时机。
 * 这与仓库既有做法一致(computeApplyEvent / computeApplyPreselect / parseWsMessage / trendChart)。
 *
 * <p><b>领域约束</b>: 「座位关注」与「选座」「锁座」是三个不同层次 ——
 * 关注是纯客户端的意图表达, **不占位、不建单、不落库、刷新即失**; 真正的锁座只能由用户确认后
 * 经既有下单入口发生。这条红线与 ADR-0002「行动卡片不执行写操作」同源。
 */

// ============ 权威重拉: 保留选座时的复核 ============

/**
 * 权威座位图重拉时复核用户已选座位。
 *
 * <p>重拉不能盲目沿用旧选择(座位可能已被别人占走), 也不能一律清空(用户正在选的座位
 * 大概率还空着, 清空等于让用户每隔几秒重选一次)。
 *
 * <p>正确做法是逐个用新位图复核: 变为已售、或变为他人锁定的移出选座集合,
 * 并加入既有的冲突闪烁集合 —— 复用既有的 1.8s 红闪与「被抢走」提示通路, 不新造机制。
 *
 * @returns lost 本次被剔除的座位索引(供 UI 提示); hasConflict 等价于 lost.length > 0
 */
export function computeRevalidateSelection(
  prevSelected: Set<number>,
  prevConflictFlash: Set<number>,
  lockBits: Uint8Array,
  soldBits: Uint8Array,
  myLockedSet: Set<number>,
): {
  selected: Set<number>
  conflictFlash: Set<number>
  lost: number[]
  hasConflict: boolean
} {
  const selected = new Set<number>()
  const conflictFlash = new Set(prevConflictFlash)
  const lost: number[] = []

  for (const idx of prevSelected) {
    if (!Number.isInteger(idx) || idx < 0 || idx >= lockBits.length) continue
    const nowSold = soldBits[idx] === 1
    const nowLockedByOther = lockBits[idx] === 1 && !myLockedSet.has(idx)
    if (nowSold || nowLockedByOther) {
      conflictFlash.add(idx)
      lost.push(idx)
      continue
    }
    selected.add(idx)
  }

  return { selected, conflictFlash, lost, hasConflict: lost.length > 0 }
}

// ============ 座位关注: 切换 ============

/** 座位关注切换的结果分支 */
export type WatchToggleReason =
  | 'added'     // 新增关注
  | 'removed'   // 取消关注
  | 'limit'     // 已达上限, 未加入
  | 'notWatchable' // 该座位不值得关注(可选 / 自己已锁)

/** 与 seat store 的 statusAt 保持一致的座位状态取值 */
export type SeatStatus = 'AVAILABLE' | 'SOLD' | 'LOCKED_OTHER' | 'LOCKED_MINE' | 'SELECTED'

/**
 * 切换某座位的关注状态。
 *
 * <p><b>顺序很重要</b>: 取消关注必须永远可用 —— 即使已经到上限, 再点一次已关注的座位
 * 也要能取消。所以先判「已关注 → 移除」, 再判上限。
 *
 * <p>只有「被占用的座位」值得关注: 已售、他人锁定。可选 / 自己已锁定的返回 `notWatchable`,
 * 由调用方回落到普通选座行为。
 */
export function computeToggleWatch(
  watched: Set<number>,
  idx: number,
  max: number,
  status: SeatStatus,
): { watched: Set<number>; reason: WatchToggleReason } {
  if (!Number.isInteger(idx) || idx < 0) {
    return { watched, reason: 'notWatchable' }
  }
  if (status !== 'SOLD' && status !== 'LOCKED_OTHER') {
    return { watched, reason: 'notWatchable' }
  }

  const next = new Set(watched)
  if (next.has(idx)) {
    next.delete(idx)
    return { watched: next, reason: 'removed' }
  }
  if (next.size >= max) {
    return { watched, reason: 'limit' }
  }
  next.add(idx)
  return { watched: next, reason: 'added' }
}

// ============ 座位关注: 释放命中 ============

/**
 * 计算一次释放事件里「被关注且确认已恢复可选」的座位。
 *
 * <p>命中集合 = 关注集合 ∩ 本次释放座位 ∩ 释放后本地状态确认已恢复可选。
 * 最后一个条件不能省: 释放事件到达的同一瞬间, 另一个用户可能已经把座位重新锁走;
 * 此时提醒「刚刚释放」会误导用户。
 *
 * <p><b>提醒是机会提示, 不是预留</b> —— 命中后由调用方结束这些座位的一次性关注,
 * 用户仍需自行选座并经服务端锁座校验。
 *
 * @returns 升序的座位索引数组(便于提示文案按排序列出)
 */
export function computeReleasedWatchHits(
  watched: Set<number>,
  releasedSeats: number[],
  lockBits: Uint8Array,
  soldBits: Uint8Array,
): number[] {
  const hits: number[] = []
  for (const s of releasedSeats) {
    if (!Number.isInteger(s) || s < 0 || s >= lockBits.length) continue
    if (!watched.has(s)) continue
    if (lockBits[s] === 1 || soldBits[s] === 1) continue   // 释放后仍不可选 → 不命中
    hits.push(s)
  }
  return hits.sort((a, b) => a - b)
}

/** 结束一批座位的一次性关注(命中释放提醒后调用) */
export function removeWatched(watched: Set<number>, seats: number[]): Set<number> {
  if (seats.length === 0) return watched
  const next = new Set(watched)
  for (const s of seats) next.delete(s)
  return next
}

/**
 * 把命中的座位索引转成可读位置列表(供提示文案用)。
 * 纯函数: 只做「索引 → 行/列文本」的拼装, 不碰 i18n(由调用方传入已本地化的格式化函数)。
 */
export function formatSeatPositions(
  seats: number[],
  cols: number,
  format: (row: number, col: number) => string,
  separator: string,
  maxShown = 4,
): { text: string; truncated: number } {
  const labels = seats.map((idx) => {
    const row = Math.floor(idx / cols) + 1
    const col = (idx % cols) + 1
    return format(row, col)
  })
  if (labels.length <= maxShown) {
    return { text: labels.join(separator), truncated: 0 }
  }
  return {
    text: labels.slice(0, maxShown).join(separator),
    truncated: labels.length - maxShown,
  }
}

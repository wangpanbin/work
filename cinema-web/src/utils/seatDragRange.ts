/**
 * 座位拖拽连选的区间计算(纯函数)。
 *
 * <p><b>为什么是纯函数</b>: 本仓 vitest 跑在 `environment: 'node'`, 没装 jsdom /
 * `@vue/test-utils`, 挂不上 SFC —— 视图层逻辑必须下沉到纯函数才可测
 * (与 `stores/seatWatch.ts` / `stores/seatEventReducer.ts` 同一套路)。
 *
 * <p><b>语义</b>: 从锚点座位向悬停座位方向取<b>同行连续可选</b>的一段。
 * 遇到任何非 `AVAILABLE` 的座位(已售 / 他人锁定 / 我锁定的 / 已选)即截断 ——
 * 连座不能跨越不可选座位, 否则会出现"看起来连着、其实中间隔着别人"的错觉。
 *
 * <p><b>边界</b>: 本函数<b>不碰</b> Pinia store, 不落任何状态。
 * 落座由调用方对区间内每个座位调既有的 `seatStore.toggle(idx)` 完成,
 * 上限与冲突判定仍由 store 说了算(见 `stores/seat.ts` 的 `toggle`)。
 */

/** 座位状态(与 `stores/seat.ts` 的 `statusAt` 返回值同形) */
export type SeatStatus = 'AVAILABLE' | 'SOLD' | 'LOCKED_OTHER' | 'LOCKED_MINE' | 'SELECTED'

export interface DragRangeResult {
  /** 区间内的座位索引(按拖拽方向排列) */
  indexes: number[]
  /** 是否被截断: 撞到不可选座位, 或超出 maxSelect 上限 */
  truncated: boolean
}

/** 空结果(常量, 避免每次 new) */
const EMPTY: DragRangeResult = { indexes: [], truncated: false }

/**
 * 计算从锚点到悬停点的同行连续可选区间。
 *
 * @param anchorIdx 锚点座位索引(按下那一刻的座位)
 * @param hoverIdx  当前指针所在座位索引
 * @param cols      每行座位数(座位索引按行主序排布)
 * @param statusOf  座位状态查询函数(调用方传 `seatStore.statusAt`)
 * @param maxSelect 本次最多还能再选几个座位(超过上限的区间会被截断)
 */
export function computeDragRange(
  anchorIdx: number,
  hoverIdx: number,
  cols: number,
  statusOf: (idx: number) => SeatStatus | string,
  maxSelect: number,
): DragRangeResult {
  if (!Number.isInteger(anchorIdx) || !Number.isInteger(hoverIdx)) return EMPTY
  if (cols <= 0 || maxSelect <= 0) return EMPTY
  if (anchorIdx < 0 || hoverIdx < 0) return EMPTY
  // 跨行不连座: 影院选座的连座是同一排内连续的
  if (Math.floor(anchorIdx / cols) !== Math.floor(hoverIdx / cols)) return EMPTY
  // 锚点必须当前可选 —— 锚在已售/他人锁定/已选上的拖拽不成立(单击切换仍走原逻辑)
  if (statusOf(anchorIdx) !== 'AVAILABLE') return EMPTY

  const step = hoverIdx >= anchorIdx ? 1 : -1
  const indexes: number[] = []
  let truncated = false

  for (let idx = anchorIdx; ; idx += step) {
    if (statusOf(idx) !== 'AVAILABLE') {
      // 撞到不可选座位: 区间到此为止
      truncated = true
      break
    }
    indexes.push(idx)
    if (idx === hoverIdx) break
    if (indexes.length >= maxSelect) {
      // 撞到本次可选上限, 但还没走到悬停点
      truncated = true
      break
    }
  }

  return { indexes, truncated }
}

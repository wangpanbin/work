import type { Id } from './index'

export interface Hall {
  id: Id
  cinemaId: Id
  name: string
  seatRows: number
  seatCols: number
  seatCount: number
  /**
   * VIP 起始行(含) —— **派生值**, 后端从 `seat` 表算出来(`MIN(row_no) WHERE seat_type=1`)。
   *
   * <p>三个值是三件事:
   * <ul>
   *   <li>`number` —— 确实有 VIP 区, 从第 N 行起;</li>
   *   <li>`null` —— 确实**没有** VIP 区;</li>
   *   <li>`undefined`(字段缺省) —— **后端没返回这个字段**(老后端), 也就是「不知道」。</li>
   * </ul>
   *
   * <p>⚠️ 这个字段是「编辑影厅会不会丢 VIP 区」的唯一凭据 —— 后端
   * `AdminHallController#update` 会删光全部座位再按 `dto.getVipFromRow()` 重插。
   * 2026-10-25 起, 后端把 `null` 的语义改成了「**保持原样**」(不是「清空 VIP 区」),
   * 所以老前端发来的 null 不会再把整厅 VIP 降级成普通座位。
   */
  vipFromRow?: number | null
}

/**
 * 影厅的提交载荷。
 *
 * <p>⚠️ `vipFromRow` 为 null 时, 后端 update 的语义是「**沿用当前 VIP 区**」
 * (不是「清空」)。想清空 VIP 区请新建影厅 —— 与「有过订单的影厅不允许改行列数」
 * 同一条规矩。create 路径不受影响: 新建时 null 就是「没有 VIP 区」。
 */
export interface HallPayload {
  cinemaId: Id
  name: string
  seatRows: number
  seatCols: number
  vipFromRow: number | null
}

export interface Session {
  id: Id
  movieId: Id
  hallId: Id
  startTime: string
  endTime: string
  price: number
  status: number
}

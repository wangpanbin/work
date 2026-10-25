import request from './request'
import { toIdString, type Id } from '../types'

export interface SeatMap {
  // ID 在 wire 上是多态的(小整数是 number, 雪花是 string) —— 见 types/index.ts 的 Id
  sessionId: Id
  movieTitle: string
  hallName: string
  startTime: string
  price: number
  rows: number
  cols: number
  vipRowNos: number[]
  seatCount: number
  lockBitmap: string
  soldBitmap: string
  myLockedSeats: number[]
  /**
   * 当前用户在该场次的**存量待支付订单号**(没有则 null / 缺省)。
   *
   * <p>锁座前服务端会 `closePending` 掉同场次的旧待支付单 —— 这个副作用
   * 对用户可见, 所以座位图接口必须把它带出来, 前端才能在发请求之前
   * 先告知用户(对话助手那条路径早有此提示, 选座这条路径原先一个字不说)。
   */
  pendingOrderNo?: string | null
  /** 该待支付订单的过期时间(epoch 毫秒) */
  pendingExpireAt?: number | null
}

export function seatMap(sessionId: Id): Promise<SeatMap> {
  return request.get(`/sessions/${toIdString(sessionId)}/seat-map`) as Promise<SeatMap>
}
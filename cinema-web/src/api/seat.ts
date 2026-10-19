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
}

export function seatMap(sessionId: Id): Promise<SeatMap> {
  return request.get(`/sessions/${toIdString(sessionId)}/seat-map`) as Promise<SeatMap>
}
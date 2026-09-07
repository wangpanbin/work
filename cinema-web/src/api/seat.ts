import request from './request'

export interface SeatMap {
  // 雪花 ID 走 string, 避免 JS Number 精度截断 (超出 2^53)
  sessionId: string
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

export function seatMap(sessionId: number | string): Promise<SeatMap> {
  return request.get(`/sessions/${sessionId}/seat-map`) as Promise<SeatMap>
}
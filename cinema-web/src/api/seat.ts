import request from './request'

export interface SeatMap {
  sessionId: number
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
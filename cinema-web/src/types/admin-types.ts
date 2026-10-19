import type { Id } from './index'

export interface Hall {
  id: Id
  cinemaId: Id
  name: string
  seatRows: number
  seatCols: number
  seatCount: number
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

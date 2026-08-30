export interface Hall {
  id: number
  cinemaId: number
  name: string
  seatRows: number
  seatCols: number
  seatCount: number
}

export interface Session {
  id: number
  movieId: number
  hallId: number
  startTime: string
  endTime: string
  price: number
  status: number
}
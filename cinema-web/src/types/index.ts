export interface R<T> {
  code: number
  msg: string
  data: T
}

export interface UserVO {
  id: number
  username: string
  nickname: string
  phone: string
}

export interface LoginVO {
  token: string
  user: UserVO
}

export interface Movie {
  id: number
  title: string
  poster: string
  duration: number
  description: string
  status: number
  createdAt?: string
}

export interface PageData<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export interface SessionVO {
  id: number
  movieId: number
  hallId: number
  hallName: string
  cinemaName: string
  startTime: string
  endTime: string
  price: number
  status: number
}

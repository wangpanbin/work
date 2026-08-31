import request from './request'
import type { Movie, PageData } from '../types'

export interface MovieSearchParams {
  page?: number
  size?: number
  status?: number
  keyword?: string
  genre?: string
  region?: string
}

export function search(params: MovieSearchParams): Promise<PageData<Movie>> {
  return request.get('/movies', { params }) as Promise<PageData<Movie>>
}

/** 兼容旧 API */
export function page(params: { page?: number; size?: number; status?: number }): Promise<PageData<Movie>> {
  return search(params)
}

export function detail(id: number | string): Promise<Movie> {
  return request.get(`/movies/${id}`) as Promise<Movie>
}

import request from './request'
import type { Movie, PageData } from '../types'

export function page(params: { page?: number; size?: number; status?: number }): Promise<PageData<Movie>> {
  return request.get('/movies', { params }) as Promise<PageData<Movie>>
}

export function detail(id: number | string): Promise<Movie> {
  return request.get(`/movies/${id}`) as Promise<Movie>
}

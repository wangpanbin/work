import request from './request'
import type { Movie } from '../types'
import type { Hall, Session } from '../types/admin-types'
import type { PageData } from '../types'

export function moviePage(params: { page?: number; size?: number; keyword?: string }): Promise<PageData<Movie>> {
  return request.get('/admin/movies', { params }) as Promise<PageData<Movie>>
}
export function movieCreate(data: Partial<Movie>): Promise<Movie> {
  return request.post('/admin/movies', data) as Promise<Movie>
}
export function movieUpdate(id: number, data: Partial<Movie>): Promise<Movie> {
  return request.put(`/admin/movies/${id}`, data) as Promise<Movie>
}
export function movieDelete(id: number): Promise<void> {
  return request.delete(`/admin/movies/${id}`) as Promise<void>
}

export function hallList(cinemaId?: number): Promise<Hall[]> {
  return request.get('/admin/halls', { params: { cinemaId } }) as Promise<Hall[]>
}
export function hallCreate(data: { cinemaId: number; name: string; seatRows: number; seatCols: number; vipFromRow?: number }): Promise<Hall> {
  return request.post('/admin/halls', data) as Promise<Hall>
}
export function hallUpdate(id: number, data: { cinemaId: number; name: string; seatRows: number; seatCols: number; vipFromRow?: number }): Promise<Hall> {
  return request.put(`/admin/halls/${id}`, data) as Promise<Hall>
}
export function hallDelete(id: number): Promise<void> {
  return request.delete(`/admin/halls/${id}`) as Promise<void>
}

export function sessionList(movieId?: number): Promise<Session[]> {
  return request.get('/admin/sessions', { params: { movieId } }) as Promise<Session[]>
}
export function sessionCreate(data: { movieId: number; hallId: number; startTime: string; price: number; status?: number }): Promise<Session> {
  return request.post('/admin/sessions', data) as Promise<Session>
}
export function sessionUpdate(id: number, data: { movieId: number; hallId: number; startTime: string; price: number; status?: number }): Promise<Session> {
  return request.put(`/admin/sessions/${id}`, data) as Promise<Session>
}
export function sessionDelete(id: number): Promise<void> {
  return request.delete(`/admin/sessions/${id}`) as Promise<void>
}
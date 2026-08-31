import request from './request'
import type { Hall, Session } from '../types/admin-types'
import type { Movie, PageData } from '../types'

/** O3 经营看板汇总 */
export interface DashboardSummary {
  todayRevenue: number
  todayOrders: number
  todayPaid: number
  todayPendingSeats: number
  todayCancelled: number
  todayRefunded: number
  weeklyTrend: Array<{ date: string; amount: number }>
  topMovies: Array<{ title: string; revenue: number; orders: number }>
  topSessions: Array<{
    sessionId: number
    movieTitle: string
    hallName: string
    startTime: string
    occupancyRate: number
  }>
}

export function dashboardSummary(): Promise<DashboardSummary> {
  return request.get('/admin/dashboard/summary') as Promise<DashboardSummary>
}

/* ===================== 影片 CRUD ===================== */

export interface AdminMovieQuery {
  page?: number
  size?: number
  keyword?: string
}

export function moviePage(params: AdminMovieQuery = {}): Promise<PageData<Movie>> {
  return request.get('/admin/movies', { params }) as Promise<PageData<Movie>>
}

export interface AdminMoviePayload {
  title: string
  poster?: string
  duration: number
  description?: string
  status?: number
  genre?: string
  region?: string
  releaseDate?: string
}

export function movieCreate(data: AdminMoviePayload): Promise<Movie> {
  return request.post('/admin/movies', data) as Promise<Movie>
}

export function movieUpdate(id: number, data: AdminMoviePayload): Promise<Movie> {
  return request.put(`/admin/movies/${id}`, data) as Promise<Movie>
}

export function movieDelete(id: number): Promise<void> {
  return request.delete(`/admin/movies/${id}`) as Promise<void>
}

/* ===================== 影厅 CRUD ===================== */

export interface AdminHallPayload {
  cinemaId: number
  name: string
  seatRows: number
  seatCols: number
  /** VIP 起始行(含); null/缺省 表示无 VIP */
  vipFromRow?: number | null
}

export function hallList(cinemaId?: number): Promise<Hall[]> {
  return request.get('/admin/halls', { params: { cinemaId } }) as Promise<Hall[]>
}

export function hallCreate(data: AdminHallPayload): Promise<Hall> {
  return request.post('/admin/halls', data) as Promise<Hall>
}

export function hallUpdate(id: number, data: AdminHallPayload): Promise<Hall> {
  return request.put(`/admin/halls/${id}`, data) as Promise<Hall>
}

export function hallDelete(id: number): Promise<void> {
  return request.delete(`/admin/halls/${id}`) as Promise<void>
}

/* ===================== 场次 CRUD ===================== */

export interface AdminSessionPayload {
  movieId: number
  hallId: number
  /** ISO 字符串: yyyy-MM-ddTHH:mm:ss */
  startTime: string
  endTime?: string
  price: number
  status?: number
}

export function sessionList(movieId?: number): Promise<Session[]> {
  return request.get('/admin/sessions', { params: { movieId } }) as Promise<Session[]>
}

export function sessionCreate(data: AdminSessionPayload): Promise<Session> {
  return request.post('/admin/sessions', data) as Promise<Session>
}

export function sessionUpdate(id: number, data: AdminSessionPayload): Promise<Session> {
  return request.put(`/admin/sessions/${id}`, data) as Promise<Session>
}

export function sessionDelete(id: number): Promise<void> {
  return request.delete(`/admin/sessions/${id}`) as Promise<void>
}

/* ===================== 场次位图恢复 (P0 P5) ===================== */

export interface RecoverResult {
  sessionId: number
  recovered: number
  seatCount: number
}

export function recoverSessionBitmap(sessionId: number): Promise<RecoverResult> {
  return request.post('/admin/sessions/bitmaps/recover', null, { params: { sessionId } }) as Promise<RecoverResult>
}

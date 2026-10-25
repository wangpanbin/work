import axios from 'axios'
import request from './request'
import type { Hall, Session } from '../types/admin-types'
import { toIdString, type Id, type Movie, type PageData } from '../types'
import { AUTH_TOKEN_KEY } from '../constants/auth'

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
    sessionId: Id
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

export function movieUpdate(id: Id, data: AdminMoviePayload): Promise<Movie> {
  return request.put(`/admin/movies/${toIdString(id)}`, data) as Promise<Movie>
}

export function movieDelete(id: Id): Promise<void> {
  return request.delete(`/admin/movies/${toIdString(id)}`) as Promise<void>
}

/* ===================== 影厅 CRUD ===================== */

export interface AdminHallPayload {
  cinemaId: Id
  name: string
  seatRows: number
  seatCols: number
  /** VIP 起始行(含); null/缺省 表示无 VIP */
  vipFromRow?: number | null
}

export function hallList(cinemaId?: Id): Promise<Hall[]> {
  return request.get('/admin/halls', { params: { cinemaId } }) as Promise<Hall[]>
}

export function hallCreate(data: AdminHallPayload): Promise<Hall> {
  return request.post('/admin/halls', data) as Promise<Hall>
}

export function hallUpdate(id: Id, data: AdminHallPayload): Promise<Hall> {
  return request.put(`/admin/halls/${toIdString(id)}`, data) as Promise<Hall>
}

export function hallDelete(id: Id): Promise<void> {
  return request.delete(`/admin/halls/${toIdString(id)}`) as Promise<void>
}

/* ===================== 场次 CRUD ===================== */

export interface AdminSessionPayload {
  movieId: Id
  hallId: Id
  /** ISO 字符串: yyyy-MM-ddTHH:mm:ss */
  startTime: string
  endTime?: string
  price: number
  status?: number
}

/**
 * 场次列表(分页) —— 2026-10-24 新增。
 *
 * <p>后端 `/api/admin/sessions` 保持返回全量 `Session[]` 不动(改了会破坏老契约),
 * 分页走这条独立路径。管理端场次表实测一次要返回 1000+ 行, 全量拉会让
 * 表格渲染与响应体都随数据量线性膨胀。
 */
export function sessionPage(params: {
  page?: number
  size?: number
  movieId?: Id
} = {}): Promise<PageData<Session>> {
  return request.get('/admin/sessions/page', { params }) as Promise<PageData<Session>>
}

export function sessionCreate(data: AdminSessionPayload): Promise<Session> {
  return request.post('/admin/sessions', data) as Promise<Session>
}

export function sessionUpdate(id: Id, data: AdminSessionPayload): Promise<Session> {
  return request.put(`/admin/sessions/${toIdString(id)}`, data) as Promise<Session>
}

export function sessionDelete(id: Id): Promise<void> {
  return request.delete(`/admin/sessions/${toIdString(id)}`) as Promise<void>
}

/* ===================== 场次位图恢复 (P0 P5) ===================== */

export interface RecoverResult {
  sessionId: Id
  recovered: number
  seatCount: number
}

export function recoverSessionBitmap(sessionId: Id): Promise<RecoverResult> {
  return request.post('/admin/sessions/bitmaps/recover', null, { params: { sessionId } }) as Promise<RecoverResult>
}

/* ===================== 营收明细导出 (T4) ===================== */

export interface RevenueExportQuery {
  /** ISO 日期 yyyy-MM-dd */
  from: string
  /** ISO 日期 yyyy-MM-dd */
  to: string
  /** 前端 UI 状态语义, 后端不二次推断 */
  mode?: 'preset' | 'custom'
}

/**
 * 下载已支付订单明细 .xlsx. 返回 Blob, 由调用方 `downloadBlob(...)` 触发浏览器下载。
 *
 * <p>自建独立 axios 实例 (不走默认 request.ts), 避开全局 JSON 响应拦截器对 blob 路径的副作用:
 * 全局拦截器会把非 0 业务码自动 ElMessage.error, 而且 `if ('code' in body)` 会把对象型 body 当 JSON 解。
 * 手抓 token + 30s timeout。
 *
 * <p>服务端业务异常时 GlobalExceptionHandler 返 JSON 包 (`Content-Type: application/json`),
 * 此处检测 Content-Type 并把 JSON body 文本抛为 Error, 防止把"业务错误 JSON"当 xlsx 下载。
 */
export async function exportRevenue(query: RevenueExportQuery): Promise<Blob> {
  const instance = axios.create({ baseURL: '/api', timeout: 30000 })
  instance.interceptors.request.use((config) => {
    const token = localStorage.getItem(AUTH_TOKEN_KEY)
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })
  const response = await instance.get('/admin/revenue/export', {
    params: query,
    responseType: 'blob',
  })
  const blob = response.data as Blob
  const ct = (response.headers['content-type'] || response.headers['Content-Type'] || '') as string
  if (ct.includes('application/json')) {
    const text = await blob.text()
    let msg = text
    try {
      const parsed = JSON.parse(text) as { code?: number; msg?: string }
      msg = parsed.msg || text
    } catch {
      // 非 JSON 文本, 直接展示
    }
    throw new Error(msg || '导出失败')
  }
  return blob
}

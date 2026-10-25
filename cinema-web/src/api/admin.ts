import axios from 'axios'
import request from './request'
import type { Hall, HallPayload, Session } from '../types/admin-types'
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

/**
 * 经营看板汇总。
 *
 * <p>`skipErrorToast: true` —— 提示权交给页面自己(2026-10-25): 本接口被实时大屏
 * 每 5 秒轮询一次, 后端一挂就是每秒一条 `ElMessage.error` 的 toast 风暴;
 * 而拦截器那条通用文案还是后端的中文原文, 英文界面会夹中文。
 * 三个调用方(经营看板 / 管理首页 / 实时大屏)都自带页内错误态与重试按钮。
 */
export function dashboardSummary(): Promise<DashboardSummary> {
  return request.get('/admin/dashboard/summary', { skipErrorToast: true }) as Promise<DashboardSummary>
}

/* ===================== 对话助手用量看板 (v0.4.0 IT-11) ===================== */

/**
 * 助手用量统计。成本字段是**估算值**(单价来自后端配置, 需人工维护), 不是财务口径。
 */
export interface ChatAssistantStats {
  days: number
  since: string
  totalCalls: number
  failedCalls: number
  failureRate: number
  sessions: number
  users: number
  promptTokens: number
  completionTokens: number
  /** 估算成本(美分 USD); null = 单价未配置或用量未知 */
  costCents: number | null
  /** P95 服务端耗时(毫秒); null = 无样本 */
  p95LatencyMs: number | null
  writeAttempts: number
  writeSuccess: number
  /** 红线指标: 未经用户显式确认的写操作数, 必须恒为 0 */
  unconfirmedWrites: number
  dailyCalls: Array<{ day: string; calls: number }>
}

/**
 * 助手用量统计。成本字段是**估算值**(单价来自后端配置, 需人工维护), 不是财务口径。
 *
 * <p>`skipErrorToast: true` —— 与 `dashboardSummary` 同一个理由(2026-10-25):
 * 调用方(助手用量看板)在页内已有自己的错误行(`admin.statsLoadFailed`),
 * 拦截器那条通用 toast 是重复的, 而且它是后端中文原文 —— 英文界面会夹中文。
 */
export function fetchChatStats(days = 7): Promise<ChatAssistantStats> {
  return request.get('/admin/chat/stats', { params: { days }, skipErrorToast: true }) as Promise<ChatAssistantStats>
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

export type { HallPayload }

/** ⚠️ 影厅载荷的字段语义见 `types/admin-types.ts` 的 `HallPayload` —— `vipFromRow` 为 null
 *  时后端按「沿用当前 VIP 区」处理, 不是「清空」。 */
export function hallList(cinemaId?: Id): Promise<Hall[]> {
  return request.get('/admin/halls', { params: { cinemaId } }) as Promise<Hall[]>
}

export function hallCreate(data: HallPayload): Promise<Hall> {
  return request.post('/admin/halls', data) as Promise<Hall>
}

export function hallUpdate(id: Id, data: HallPayload): Promise<Hall> {
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

import request from './request'
import type { PageData } from '../types'

export interface OrderVO {
  orderNo: string
  // 雪花 ID 走 string, 避免 JS Number 精度截断
  sessionId: string
  status: number
  statusText: string
  movieTitle: string
  hallName: string
  startTime: string
  seatIndexes: number[] | null
  seatDesc: string
  totalAmount: number
  seatCount: number
  expireAt: string
  paidAt: string | null
  createdAt: string
}

export interface LockResult {
  orderNo: string
  expireAt: string
  totalAmount: number
  seatIndexes: number[]
}

export function lockSeats(data: { sessionId: string | number; seatIndexes: number[] }): Promise<LockResult> {
  return request.post('/orders/lock', data) as Promise<LockResult>
}

export function myOrders(params: { status?: number; page?: number; size?: number }): Promise<PageData<OrderVO>> {
  return request.get('/orders/my', { params }) as Promise<PageData<OrderVO>>
}

export function orderDetail(orderNo: string): Promise<OrderVO> {
  return request.get(`/orders/${orderNo}`) as Promise<OrderVO>
}

export function pay(orderNo: string): Promise<void> {
  return request.post(`/orders/${orderNo}/pay`) as Promise<void>
}

export function cancel(orderNo: string): Promise<void> {
  return request.post(`/orders/${orderNo}/cancel`) as Promise<void>
}

/** N1 退票: PAID → REFUNDING → REFUNDED */
export function refund(orderNo: string): Promise<void> {
  return request.post(`/orders/${orderNo}/refund`) as Promise<void>
}

export interface TicketVO {
  /** base64-url 编码的 payload(JSON 字符串) */
  payload: string
  /** base64-url 编码的 HMAC-SHA256 签名 */
  sig: string
  /** 24h 过期时间 */
  expAt: string
  orderNo: string
}

/** N2 取电子票(仅 PAID 状态可取) */
export function getTicket(orderNo: string): Promise<TicketVO> {
  return request.get(`/orders/${orderNo}/ticket`) as Promise<TicketVO>
}
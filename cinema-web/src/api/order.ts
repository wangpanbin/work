import request from './request'
import type { PageData } from '../types'

export interface OrderVO {
  orderNo: string
  sessionId: number
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

export function lockSeats(data: { sessionId: number; seatIndexes: number[] }): Promise<LockResult> {
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
import request from './request'
import type { Hall, Session } from '../types/admin-types'
import type { SessionVO } from '../types'

export type { Hall, Session }

export function listByMovieAndDate(movieId: number | string, date?: string): Promise<SessionVO[]> {
  return request.get(`/movies/${movieId}/sessions`, { params: { date } }) as Promise<SessionVO[]>
}
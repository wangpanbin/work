import request from './request'
import type { Hall, Session } from '../types/admin-types'

export type { Hall, Session }

export function listByMovieAndDate(movieId: number | string, date?: string): Promise<Session[]> {
  return request.get(`/movies/${movieId}/sessions`, { params: { date } }) as Promise<Session[]>
}
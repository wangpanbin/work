import request from './request'
import type { Hall, Session } from '../types/admin-types'
import { toIdString, type Id, type SessionVO } from '../types'

export type { Hall, Session }

export function listByMovieAndDate(movieId: Id, date?: string): Promise<SessionVO[]> {
  return request.get(`/movies/${toIdString(movieId)}/sessions`, { params: { date } }) as Promise<SessionVO[]>
}
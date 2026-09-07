import request from './request'
import type { LoginVO, UserVO } from '../types'

export function login(data: { username: string; password: string }): Promise<LoginVO> {
  return request.post('/auth/login', data) as Promise<LoginVO>
}

export function register(data: {
  username: string
  password: string
  nickname?: string
  phone?: string
}): Promise<void> {
  return request.post('/auth/register', data) as Promise<void>
}

export function me(): Promise<UserVO> {
  return request.get('/users/me') as Promise<UserVO>
}

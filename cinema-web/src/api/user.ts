import request from './request'
import type { LoginVO, UserVO } from '../types'

/**
 * 登录。
 *
 * <p>⚠️ `skipErrorToast: true` —— 登录页自己弹提示(42900 限流走 ElMessageBox,
 * 其它失败走 ElMessage.error), 拦截器那条通用 toast 必须让位,
 * 否则同一次失败弹两条(通用那条还是后端中文原文, 英文界面会夹中文)。
 *
 * <p>⚠️ 注册**不要**照抄这个开关 —— 注册没有业务层的专门提示,
 * 声明了 skipErrorToast 就等于把「用户名已存在」吞掉, 用户看不到任何反馈。
 */
export function login(data: { username: string; password: string }): Promise<LoginVO> {
  return request.post('/auth/login', data, { skipErrorToast: true }) as Promise<LoginVO>
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

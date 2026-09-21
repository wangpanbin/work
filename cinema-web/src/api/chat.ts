import request from './request'

/** 发送聊天消息, 获取 AI 回复 */
export function sendMessage(message: string): Promise<{ reply: string }> {
  return request.post('/chat', { message }) as Promise<{ reply: string }>
}

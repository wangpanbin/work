import request from './request'
import type { ChatRequestDTO, ChatResponseVO } from '../types'

/**
 * 对话式订票助手 API (spec §6.1, ticket #12 T6).
 *
 * <p>鉴权由 api/request.ts 拦截器自动处理:有 cinema_token 时加 Authorization,
 * 无 token 也可调(后端 50000 短路)— 不需要在本文件重复。
 *
 * <p>chatSessionId 用 localStorage 持久化,首次进浮窗时用 crypto.randomUUID()
 * 生成(spec §4.4 决策)。无 UUID 时由后端 fallback 生成(见 spec §6.1 注释)。
 */
const CHAT_SESSION_KEY = 'cinema_chat_session_id'

function getOrCreateChatSessionId(): string {
  let id = localStorage.getItem(CHAT_SESSION_KEY)
  if (!id) {
    // crypto.randomUUID 是现代浏览器 API(Vue 3 / TS 5.7 都可用)
    id = crypto.randomUUID()
    localStorage.setItem(CHAT_SESSION_KEY, id)
  }
  return id
}

/**
 * 发送聊天消息。
 *
 * <p>走 api/request.ts 拦截器链(自动 token / 自动 code 解包),
 * code != 0 时被拦截器翻译成 ElMessage 错误 + Promise reject。
 */
export function sendMessage(dto: Omit<ChatRequestDTO, 'chatSessionId'>): Promise<ChatResponseVO> {
  const payload: ChatRequestDTO = {
    chatSessionId: getOrCreateChatSessionId(),
    message: dto.message,
    context: dto.context,
  }
  return request.post('/chat/message', payload) as Promise<ChatResponseVO>
}

/**
 * 重置 chatSessionId(下次发消息时由后端 fallback 新建)。
 * 主要用于调试 / 用户主动"清空对话"。
 */
export function resetChatSession(): void {
  localStorage.removeItem(CHAT_SESSION_KEY)
}
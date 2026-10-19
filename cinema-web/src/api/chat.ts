import request from './request'
import type { ChatRequestDTO, ChatResponseVO, Id, OrderProposalVO } from '../types'

/**
 * 对话式订票助手 API (spec §6.1, ticket #12 T6)。
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
  return (request.post('/chat/message', payload) as Promise<ChatResponseVO>).then((res) => {
    // spec §6.1: 请求没带 id 时后端生成 fallback 并回传,下次请求复用它。
    // 正常情况下前端总会带 id,这里是个无害的 no-op 兜底。
    if (res?.chatSessionId && res.chatSessionId !== payload.chatSessionId) {
      localStorage.setItem(CHAT_SESSION_KEY, res.chatSessionId)
    }
    return res
  })
}

/**
 * 重置 chatSessionId(下次发消息时由后端 fallback 新建)。
 * 主要用于调试 / 用户主动"清空对话"。
 */
export function resetChatSession(): void {
  localStorage.removeItem(CHAT_SESSION_KEY)
}

// ============ 一键下单 (ADR-0004) ============

/**
 * 重新找一组座位。
 *
 * <p>只有场次与张数 —— **座位由服务端重读位图挑, 客户端无从指定**。
 * 端点返回的是一份服务端提案(座位 / 单价 / 总额 / 过期时刻), 确认时凭
 * {@code proposalId} 回服务端取这份快照, 前端不回传任何座位。
 *
 * <p>{@code sessionId} 用 string 传(雪花 ID 是 19 位十进制数, 转成 number
 * 会丢精度); 后端 DTO 是 Long, Jackson 能直接吃字符串。
 */
export function createOrderProposal(dto: { sessionId: Id; count: number }): Promise<OrderProposalVO> {
  return request.post('/chat/orders/proposals', dto) as Promise<OrderProposalVO>
}

/**
 * 确认下单 —— 按服务端快照锁座, 订单进入「待支付」。
 *
 * <p>请求体只有一个 {@code proposalId}: 座位、单价、总额全在服务端快照里。
 * 幂等键也是 {@code proposalId}, 所以重复点不会锁两次座。
 */
export function confirmOrderProposal(proposalId: string): Promise<OrderProposalVO> {
  return request.post(`/chat/orders/${encodeURIComponent(proposalId)}/confirm`) as Promise<OrderProposalVO>
}
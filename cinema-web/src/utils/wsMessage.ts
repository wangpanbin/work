import type { SeatEvent } from './ws'

/**
 * ws.ts 的 WS 帧协议层抽出: 把 raw text → SeatEvent | null.
 *
 * <p>#6 收尾: WebSocket 测试的最大障碍是 side effects(open/close/heartbeat);
 * 抽出协议解析后, 单元测试可以零 mock 锁定:
 * <ul>
 *   <li>PONG 心跳识别</li>
 *   <li>非法 type 拒绝</li>
 *   <li>非数组 seats 拒绝</li>
 *   <li>sessionId string 接受(雪花 ID)</li>
 *   <li>JSON parse 失败不抛</li>
 * </ul>
 */
export function parseWsMessage(raw: string): SeatEvent | null {
  if (!raw) return null
  if (raw === 'PONG') return null
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isSeatEvent(parsed)) return null
  return parsed
}

function isSeatEvent(v: unknown): v is SeatEvent {
  if (typeof v !== 'object' || v === null) return false
  const o = v as Record<string, unknown>
  if (o.type !== 'LOCKED' && o.type !== 'RELEASED' && o.type !== 'SOLD') return false
  if (typeof o.sessionId !== 'string' && typeof o.sessionId !== 'number') return false
  if (!Array.isArray(o.seats)) return false
  if (!o.seats.every((s) => typeof s === 'number')) return false
  return true
}
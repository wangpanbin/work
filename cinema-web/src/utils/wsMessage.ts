import type { SeatEvent } from './ws'

/**
 * ws.ts 的 WS 帧协议层抽出: 把 raw text → SeatEvent | null。
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
  // 宽松透传 seq: 仅当它是有限数值才挂到返回对象上; 缺失或非法则**剥掉该字段**。
  //   ① 剥掉而不是原样放行 —— 否则 seq:"42" / null / Infinity 会残留在事件对象上,
  //      下游虽然仍会按"无序号"处理, 但对象形状不再可信, 任何 toEqual 断言都会误伤;
  //   ② 剥掉也让"无序号"在下游有明确表示, 下游一律"总是应用";
  //   ③ 前后端版本不一致时退化成改动前的纯增量行为, 而不是静默失同步。
  const { seq, ...rest } = parsed as Record<string, unknown>
  if (typeof seq === 'number' && Number.isFinite(seq)) {
    return { ...rest, seq } as SeatEvent
  }
  return rest as SeatEvent
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
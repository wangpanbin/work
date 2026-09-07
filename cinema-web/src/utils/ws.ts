/**
 * 座位图 WebSocket 客户端: 自动重连 + 心跳 + 事件回调
 * 后端路径 /ws/seat/{sessionId} (已公开, 无需 token)
 * <p>Phase C-⑮ 优化: 把 setInterval id 存到 pingTimer,
 * onclose/close 时都 clearInterval, 避免重连过程中累积多个心跳 timer.
 * <p>P0-2 新增: 暴露 status 响应式 ref, 让 UI 能展示连接状态 (connecting/open/closed)
 * 并对被他人抢走的已选座位做"刚被抢"的临时标记.
 */
import { ref } from 'vue'

export type SeatEvent = {
  type: 'LOCKED' | 'RELEASED' | 'SOLD'
  // 雪花 ID 走 string, 避免 JS Number 精度截断
  sessionId: string | number
  seats: number[]
}

export type WsStatus = 'connecting' | 'open' | 'closed'

export function createSeatWs(
  sessionId: number | string,
  onEvent: (e: SeatEvent) => void,
) {
  let ws: WebSocket | null = null
  let retryDelay = 1000
  let retryTimer: number | null = null
  let pingTimer: number | null = null
  let closed = false

  // P0-2: 连接状态外露, 组件可订阅
  const status = ref<WsStatus>('connecting')
  /** 当前冲突座位(本轮刚被他人抢走的) — 用于触发 UI 闪烁/提示 */
  const lastConflict = ref<number[]>([])

  const clearPing = () => {
    if (pingTimer != null) {
      clearInterval(pingTimer)
      pingTimer = null
    }
  }

  const connect = () => {
    if (closed) return
    status.value = 'connecting'
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const host = location.hostname
    const port = location.port === '5173' ? '8080' : location.port
    ws = new WebSocket(`${proto}://${host}:${port}/ws/seat/${sessionId}`)

    ws.onmessage = (msg) => {
      try {
        const payload = msg.data
        if (payload === 'PONG') return
        const evt = JSON.parse(payload) as SeatEvent
        if (evt && evt.type && Array.isArray(evt.seats)) {
          onEvent(evt)
          // P0-2: 收到 LOCKED/SOLD 时把 seats 暂存到 lastConflict, 让 UI 提示
          if (evt.type === 'LOCKED' || evt.type === 'SOLD') {
            lastConflict.value = evt.seats
          }
        }
      } catch {
        // 忽略解析错误
      }
    }

    ws.onclose = () => {
      clearPing()
      if (closed) {
        status.value = 'closed'
        return
      }
      status.value = 'closed'   // 提示用户当前已断开, 正在重连
      retryTimer = window.setTimeout(connect, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 10000) // 指数退避到 10s
    }

    ws.onerror = () => {
      ws?.close()
    }

    ws.onopen = () => {
      retryDelay = 1000
      status.value = 'open'
      clearPing()
      // P2-#18: 心跳 25s → 15s, 避免被部分网关 30s idle 切断导致抢票场景下静默失同步
      pingTimer = window.setInterval(() => {
        if (ws?.readyState === WebSocket.OPEN) {
          ws.send('PING')
        }
      }, 15000)
    }
  }

  connect()

  return {
    close() {
      closed = true
      clearPing()
      if (retryTimer) clearTimeout(retryTimer)
      status.value = 'closed'
      ws?.close()
    },
    /** P0-2: 暴露给组件, 状态变化会自动驱动 UI */
    status,
    lastConflict,
  }
}

/** WebSocket 句柄: 暴露 close() + status/lastConflict 供组件订阅 */
export type SeatWsHandle = ReturnType<typeof createSeatWs>

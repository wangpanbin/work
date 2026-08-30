/**
 * 座位图 WebSocket 客户端: 自动重连 + 心跳 + 事件回调
 * 后端路径 /ws/seat/{sessionId} (已公开, 无需 token)
 */
export type SeatEvent = {
  type: 'LOCKED' | 'RELEASED' | 'SOLD'
  sessionId: number
  seats: number[]
}

export function createSeatWs(sessionId: number | string, onEvent: (e: SeatEvent) => void) {
  let ws: WebSocket | null = null
  let retryDelay = 1000
  let retryTimer: number | null = null
  let closed = false

  const connect = () => {
    if (closed) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const host = location.hostname
    const port = location.port === '5173' ? '8080' : location.port
    ws = new WebSocket(`${proto}://${host}:${port}/ws/seat/${sessionId}`)

    ws.onmessage = (msg) => {
      try {
        const payload = msg.data
        if (payload === 'PONG') return
        const evt = JSON.parse(payload) as SeatEvent
        if (evt && evt.type && Array.isArray(evt.seats)) onEvent(evt)
      } catch {
        // 忽略解析错误
      }
    }

    ws.onclose = () => {
      if (closed) return
      retryTimer = window.setTimeout(connect, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 10000) // 指数退避到 10s
    }

    ws.onerror = () => {
      ws?.close()
    }

    ws.onopen = () => {
      retryDelay = 1000
      setInterval(() => {
        if (ws?.readyState === WebSocket.OPEN) {
          ws.send('PING')
        }
      }, 25000)
    }
  }

  connect()

  return {
    close() {
      closed = true
      if (retryTimer) clearTimeout(retryTimer)
      ws?.close()
    },
  }
}
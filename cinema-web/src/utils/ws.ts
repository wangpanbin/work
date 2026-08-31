/**
 * 座位图 WebSocket 客户端: 自动重连 + 心跳 + 事件回调
 * 后端路径 /ws/seat/{sessionId} (已公开, 无需 token)
 * <p>Phase C-⑮ 优化: 把 setInterval id 存到 pingTimer,
 * onclose/close 时都 clearInterval, 避免重连过程中累积多个心跳 timer.
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
  let pingTimer: number | null = null
  let closed = false

  const clearPing = () => {
    if (pingTimer != null) {
      clearInterval(pingTimer)
      pingTimer = null
    }
  }

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
      // 关闭时清掉心跳, 避免新连接开始又叠加
      clearPing()
      if (closed) return
      retryTimer = window.setTimeout(connect, retryDelay)
      retryDelay = Math.min(retryDelay * 2, 10000) // 指数退避到 10s
    }

    ws.onerror = () => {
      ws?.close()
    }

    ws.onopen = () => {
      retryDelay = 1000
      // 每次 onopen 重新建一份心跳, 避免重连后叠加
      clearPing()
      pingTimer = window.setInterval(() => {
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
      clearPing()
      if (retryTimer) clearTimeout(retryTimer)
      ws?.close()
    },
  }
}

/** WebSocket 句柄: 仅暴露 close() 供组件卸载时清理 */
export type SeatWsHandle = ReturnType<typeof createSeatWs>

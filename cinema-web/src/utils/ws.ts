/**
 * 座位图 WebSocket 客户端: 自动重连 + 心跳 + 事件回调
 * 后端路径 /ws/seat/{sessionId} (已公开, 无需 token)
 * <p>Phase C-⑮ 优化: 把 setInterval id 存到 pingTimer,
 * onclose/close 时都 clearInterval, 避免重连过程中累积多个心跳 timer.
 * <p>P0-2 新增: 暴露 status 响应式 ref, 让 UI 能展示连接状态 (connecting/open/closed)
 * 并对被他人抢走的已选座位做"刚被抢"的临时标记.
 */
import { ref } from 'vue'
import { parseWsMessage } from './wsMessage'

export type SeatEvent = {
  type: 'LOCKED' | 'RELEASED' | 'SOLD'
  // 雪花 ID 走 string, 避免 JS Number 精度截断
  sessionId: string | number
  seats: number[]
  /**
   * 全局单调事件序号(服务端 Redis INCR 产生).
   *
   * <p>**可选**: 缺失或类型非法时视为「无序号」, 事件总是应用。
   * `seq <= 0` 是服务端自增失败时的兜底值, 同样按「无序号」处理 ——
   * 若把它当普通序号参与比较, `0 > lastSeq[s]` 恒为假, 会把所有事件静默丢弃。
   *
   * <p>序号只在**同一座位上**提供先后关系, 不提供全序(两个并发请求谁先 PUBLISH 不确定),
   * 因此比较必须按座位分别进行, 见 seatEventReducer 的 filterBySeq。
   */
  seq?: number
}

export type WsStatus = 'connecting' | 'open' | 'closed'

/**
 * 每次 connect() 开始时应写入的状态。
 *
 * ⚠️ 重连期间必须保持 'closed', 不能写 'connecting'。
 * 组件(SeatSelect)靠 `status` 从 'closed' 直接翻到 'open' 来判定
 * "这是一次断线重连", 从而在重连后立刻做一次权威重拉, 补偿断连期间丢失的事件。
 * 若 connect() 无条件写 'connecting', onopen 时 prev 恒为 'connecting',
 * 那个判定永远为假 → 重连补偿形同虚设, 用户会一直看着一张过期座位图
 * 而毫无察觉。2026-10-19 浏览器验收实测过这个 bug: 停掉后端 → 重连成功,
 * 网络面板里一条 seat-map 请求都没有, 位图仍是断连前的旧值。
 */
export function statusOnConnect(everOpened: boolean): WsStatus {
  return everOpened ? 'closed' : 'connecting'
}

export function createSeatWs(
  sessionId: number | string,
  onEvent: (e: SeatEvent) => void,
) {
  let ws: WebSocket | null = null
  let retryDelay = 1000
  let retryTimer: number | null = null
  let pingTimer: number | null = null
  let closed = false
  /** 是否至少成功建连过一次 — 用来区分「首次建连」与「断线重连」 */
  let everOpened = false

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
    status.value = statusOnConnect(everOpened)
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const host = location.hostname
    const port = location.port === '5173' ? '8080' : location.port
    ws = new WebSocket(`${proto}://${host}:${port}/ws/seat/${sessionId}`)

    ws.onmessage = (msg) => {
      const payload = msg.data
      const evt = parseWsMessage(payload)
      if (!evt) return
      onEvent(evt)
      // P0-2: 收到 LOCKED/SOLD 时把 seats 暂存到 lastConflict, 让 UI 提示
      if (evt.type === 'LOCKED' || evt.type === 'SOLD') {
        lastConflict.value = evt.seats
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
      everOpened = true
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

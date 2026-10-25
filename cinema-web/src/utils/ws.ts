/**
 * 座位图 WebSocket 客户端: 自动重连 + 心跳 + 事件回调
 * 后端路径 /ws/seat/{sessionId} (已公开, 无需 token)
 * <p>Phase C-⑮ 优化: 把 setInterval id 存到 pingTimer,
 * onclose/close 时都 clearInterval, 避免重连过程中累积多个心跳 timer。
 * <p>P0-2 新增: 暴露 status 响应式 ref, 让 UI 能展示连接状态 (connecting/open/closed)
 * 并对被他人抢走的已选座位做"刚被抢"的临时标记。
 */
import { ref } from 'vue'
import { parseWsMessage } from './wsMessage'
import { toIdString, type Id } from '../types'

export type SeatEvent = {
  type: 'LOCKED' | 'RELEASED' | 'SOLD'
  // 雪花 ID 走 string, 避免 JS Number 精度截断
  sessionId: string | number
  seats: number[]
  /**
   * 全局单调事件序号(服务端 Redis INCR 产生)。
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

/**
 * WebSocket 基址解析(2026-10-24)—— 座位图与大屏**共用**这一份。
 *
 * <p>原先两处各写一套策略:
 * <ul>
 *   <li>座位 WS({@link createSeatWs}): `location.hostname` + 端口 5173 时换成 8080
 *       —— 直连后端, 不走 Vite 代理;</li>
 *   <li>大屏 WS(LiveDashboard.vue): 直接用 `location.host` —— 依赖 Vite 代理转发 /ws。</li>
 * </ul>
 * 两套策略不一致 → 同一个后端, 两条 socket 走不同路径; 而 {@code vite preview} 只配了
 * {@code server.proxy}、**没有** {@code preview.proxy}, 于是 preview 模式下大屏那条
 * 彻底连不上, 且没有任何错误面(用户只看到大屏一直空着)。
 *
 * <p>统一规则: 开发服务器端口(5173 / 4173)→ 直连后端 8080; 其余情况用当前 host。
 *
 * @param loc 形如 {@code location} 的对象(只用到 protocol / hostname / port)
 */
export function wsBaseUrl(loc: { protocol: string; hostname: string; port: string }): string {
  const proto = loc.protocol === 'https:' ? 'wss' : 'ws'
  const DEV_PORTS = ['5173', '4173']
  const port = DEV_PORTS.includes(loc.port) ? '8080' : loc.port
  return port ? `${proto}://${loc.hostname}:${port}` : `${proto}://${loc.hostname}`
}

export function createSeatWs(
  sessionId: Id,
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

  const clearPing = () => {
    if (pingTimer != null) {
      clearInterval(pingTimer)
      pingTimer = null
    }
  }

  const connect = () => {
    if (closed) return
    status.value = statusOnConnect(everOpened)
    // 基址解析与「大屏」共用一份(见 wsBaseUrl 的说明)
    ws = new WebSocket(`${wsBaseUrl(location)}/ws/seat/${toIdString(sessionId)}`)
    ws.onmessage = (msg) => {
      const payload = msg.data
      const evt = parseWsMessage(payload)
      if (!evt) return
      onEvent(evt)
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
  }
}

/** WebSocket 句柄: 暴露 close() + status 供组件订阅 */
export type SeatWsHandle = ReturnType<typeof createSeatWs>

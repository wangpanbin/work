export interface R<T> {
  code: number
  msg: string
  data: T
}

export interface UserVO {
  id: number
  username: string
  nickname: string
  phone: string
  /** 角色: 0-普通用户, 1-管理员 */
  role: number
}

export interface LoginVO {
  token: string
  user: UserVO
}

export interface Movie {
  id: number
  title: string
  poster: string
  duration: number
  description: string
  status: number
  createdAt?: string
}

export interface PageData<T> {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export interface SessionVO {
  id: number
  movieId: number
  hallId: number
  hallName: string
  cinemaName: string
  startTime: string
  endTime: string
  price: number
  status: number
}

// ============ 对话式订票助手 (chat-assistant T6) ============

/**
 * 行动卡片 (ActionCardVO schema, spec §6.1)。
 * 卡片只携带展示与跳转所需的信息,不携带任何可直接提交的载荷(ADR-0002)。
 */
export interface ActionCardVO {
  type: string
  /** 雪花 ID 字符串(后端强类型是 Long,但前端 URL 走 String — 雪花 ID 超 2^53) */
  sessionId: string
  movieTitle?: string
  hallName?: string
  /** yyyy-MM-dd HH:mm:ss 格式 */
  startTime?: string
  price?: number
  /** 座位索引列表(整数) */
  seatIndexes?: number[]
  /** 行列表述,展示用,如 "5排4座、5排5座" */
  seatDesc?: string
  totalAmount?: number
  /** 卡片按钮文案,如 "去选座确认" */
  actionLabel?: string

  // ============ ADR-0004:一键下单确认卡专用字段(既有卡型一律为 undefined)============

  /**
   * 下单提案 ID(服务端生成的 UUID)。
   *
   * 它是「服务端快照的引用」, 不是可直接提交的载荷 —— 确认端点只凭这个 ID
   * 去后端取服务端自己算好的座位与价格, 前端不回传任何座位。
   */
  proposalId?: string
  /** 提案过期时刻(epoch ms), 卡片用它渲染倒计时 */
  expiresAt?: number
  /** 提案张数(1~4) */
  count?: number
  /** 确认后将被取消的同场次待支付订单号 —— 非空时卡片必须显式提示 */
  pendingCancelOrderNo?: string
  /** 上述待支付单的支付截止时刻(epoch ms) */
  pendingCancelExpireAt?: number
  /** 稳定的卡片标识(后端装配器生成), 用于 v-for key */
  cardId?: string

  // ============ 锁座成功后的「待支付卡」字段(前端本地生成, 不来自模型)============

  /** 已创建的订单号 */
  orderNo?: string
  /** 支付窗口截止时刻(epoch ms) */
  orderExpireAt?: number
}

/** 下单提案 (OrderProposalVO schema, ADR-0004) */
export interface OrderProposalVO {
  proposalId: string
  /** 场次 ID 是 String —— 雪花 ID 超 2^53, 用 number 会静默丢精度 */
  sessionId: string
  seatIndexes?: number[]
  seatDesc?: string
  unitPrice?: number
  totalAmount?: number
  movieTitle?: string
  hallName?: string
  startTime?: string
  /** 提案过期时刻(epoch ms) */
  expiresAt?: number
  /** 确认后将被取消的同场次待支付订单号 */
  pendingCancelOrderNo?: string
  /** 上述待支付单的支付截止时刻(epoch ms) */
  pendingCancelExpireAt?: number
  /** 已创建的订单号(仅确认成功后有) */
  orderNo?: string
  /** 支付窗口截止时刻(epoch ms, 仅确认成功后有) */
  orderExpireAt?: number
}

/** 聊天响应 (ChatResponseVO schema) */
export interface ChatResponseVO {
  /**
   * 后端实际使用的会话 id (spec §6.1)。请求没带 chatSessionId 时后端生成
   * fallback 并通过本字段回传,客户端下次请求应复用。可选 —— 旧后端不返回。
   */
  chatSessionId?: string
  reply: string
  cards: ActionCardVO[]
  followUps: string[]
}

/** 场次上下文 (ChatContextDTO schema,spec §6.1) — 让"这个还有座吗"等指代可解析 */
export interface ChatContextDTO {
  route: string
  /** 场次 ID 字符串,与 chatSessionId 是两个字段 */
  sessionId: string | null
  seatCount: number
  selectedSeatIndexes: number[]
}

/** 聊天请求 (ChatRequestDTO schema) */
export interface ChatRequestDTO {
  /** 前端首次进浮窗用 crypto.randomUUID() 生成,localStorage 持久化 */
  chatSessionId: string
  message: string
  context: ChatContextDTO | null
}

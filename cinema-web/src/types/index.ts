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
 * 行动卡片 (ActionCardVO schema, spec §6.1).
 * 卡片只携带展示与跳转所需的信息,不携带任何可直接提交的载荷(ADR-0002).
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
}

/** 聊天响应 (ChatResponseVO schema) */
export interface ChatResponseVO {
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

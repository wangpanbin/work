/**
 * 全站业务错误码 —— 唯一归属。
 *
 * <p>与后端 `com.cinema.common.result.ResultCode` 一一对应。
 * 改这里必须同步改那边; 反之亦然。
 *
 * <p>**为什么不让调用方直接写数字**: 锁座冲突过去靠中文消息串识别
 * (`msg.includes('座位已被占用')`), 后端改一次文案就静默失效 ——
 * 错误码是唯一稳定的判别依据, 文案是给人看的, 随时会改。
 */

/** 参数错误 */
export const BAD_REQUEST_CODE = 40001
/** 锁座冲突 —— 座位被别人抢走 */
export const SEAT_CONFLICT_CODE = 40002
/** 对话下单提案已过期 / 已被消费 */
export const PROPOSAL_EXPIRED_CODE = 40003
/** 下单功能被回滚闸门关掉(`cinema.chat.order-enabled=false`) */
export const ORDER_DISABLED_CODE = 40004
/** 未登录 */
export const UNAUTHORIZED_CODE = 40101
/** 登录已过期 */
export const TOKEN_EXPIRED_CODE = 40102
/** 无权限 */
export const FORBIDDEN_CODE = 40301
/** 幂等冲突 —— 重复提交 */
export const IDEMPOTENT_CONFLICT_CODE = 40900
/** 限流 */
export const RATE_LIMIT_CODE = 42900
/** 服务端错误 */
export const SYSTEM_ERROR_CODE = 50000

/**
 * 业务异常 —— `request.ts` 抛出, 调用方消费。
 *
 * <p>`code` 是后端业务码; `data` 是业务载荷
 * (例如锁座冲突的座位列表在 `data.conflict`)。
 *
 * <p>网络层错误(断网 / 超时)没有 `code`, 走的是 axios 自己的异常。
 */
export type ApiError = Error & {
  code?: number
  data?: unknown
}

/** 锁座冲突的业务载荷 —— 冲突座位索引列表 */
export type SeatConflictPayload = { conflict?: number[] }

/**
 * 是不是带业务码的业务异常。
 *
 * <p>网络层错误没有 `code`, 返 false。
 */
export function isApiError(e: unknown): e is ApiError {
  return e instanceof Error && typeof (e as ApiError).code === 'number'
}

/**
 * 是不是「座位被抢」(锁座冲突)。
 *
 * <p>判据是 **错误码 + 载荷**, 不是中文消息串。
 *
 * <p>为什么不能只看 40002: 它是通用业务错误码, 后端 `BizException(String)`
 * 的默认码就是它 —— 订单不存在 / 场次不存在 / 场次已开场 全都是 40002。
 * 单看码会把这些误判成座位冲突。
 *
 * <p>为什么还要看载荷: `data.conflict` 全仓**只在锁座冲突这一条路径**写入
 * (后端 `OrderLockService` 抛异常时带 `{conflict: [...]}`), 所以两个条件合起来是精确的。
 */
export function isSeatConflict(e: unknown): e is ApiError {
  if (!isApiError(e) || e.code !== SEAT_CONFLICT_CODE) return false
  const data = e.data as SeatConflictPayload | undefined
  return Array.isArray(data?.conflict)
}

/**
 * 取锁座冲突的座位列表。
 *
 * @returns 冲突座位索引; 不是锁座冲突时返 null
 */
export function seatConflictOf(e: unknown): number[] | null {
  if (!isSeatConflict(e)) return null
  const data = e.data as SeatConflictPayload
  return data.conflict ?? []
}

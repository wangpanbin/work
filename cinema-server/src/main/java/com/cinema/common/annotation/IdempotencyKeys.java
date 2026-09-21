package com.cinema.common.annotation;

/**
 * @Idempotent / @RateLimit 的 SpEL key 常量集中.
 *
 * <p>#7 收尾: 原本 4 个 controller 端点各写一遍 SpEL 串, 加新端点跨同模板;
 * 这里集中 5 个高频模式(锁定/支付/取消/退票 + 锁定的 rate limit),
 * 单一来源避免拼写漂移.
 *
 * <p>注意: Java annotation 的 String 值必须 compile-time constant, 所以这里只是
 * String literal holder, 不能用 String.format() 之类运行时拼接.
 * 真正的"运行时 helper"(如 IdempotencyKey.byUserAndOrderNo(op, orderNo))
 * 仍是 Phase 2 的工作 — 本类先立 seam.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    /** 锁座幂等: userId + sessionId + seatIndexes(去重序列化).
     *  P0 修复: 必须带 userId(否则 user1/user2 锁同一座位会撞同一个 key 互锁). */
    public static final String LOCK_BY_USER_SESSION_SEATS =
            "T(com.cinema.common.context.UserContext).userId() + ':' + #dto.sessionId + ':' + "
                    + "(#dto.seatIndexes != null ? #dto.seatIndexes.toString() : 'empty')";

    /** 支付幂等: orderNo 本身唯一 */
    public static final String PAY_BY_ORDER_NO = "#orderNo";

    /** 退票幂等: orderNo + ':refund'(区别于支付) */
    public static final String REFUND_BY_ORDER_NO = "#orderNo + ':refund'";

    /** 锁座 rate limit: userId + ':lock:' + sessionId */
    public static final String RATE_LOCK_BY_USER_SESSION =
            "T(com.cinema.common.context.UserContext).userId() + ':lock:' + #dto.sessionId";

    /** 支付 rate limit: userId + ':pay:' + orderNo */
    public static final String RATE_PAY_BY_USER_ORDER =
            "T(com.cinema.common.context.UserContext).userId() + ':pay:' + #orderNo";

    /** 取消 rate limit: userId + ':cancel:' + orderNo */
    public static final String RATE_CANCEL_BY_USER_ORDER =
            "T(com.cinema.common.context.UserContext).userId() + ':cancel:' + #orderNo";

    /** 退票 rate limit: userId + ':refund:' + orderNo */
    public static final String RATE_REFUND_BY_USER_ORDER =
            "T(com.cinema.common.context.UserContext).userId() + ':refund:' + #orderNo";
}
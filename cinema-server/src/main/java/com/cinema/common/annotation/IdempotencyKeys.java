package com.cinema.common.annotation;

/**
 * @Idempotent / @RateLimit 的 SpEL key 常量集中。
 *
 * <p>#7 收尾: 原本 4 个 controller 端点各写一遍 SpEL 串, 加新端点跨同模板;
 * 这里集中 8 个高频模式(锁定/支付/取消/退票 + 锁定的 rate limit
 * + 对话下单的确认幂等与两个 rate limit), 单一来源避免拼写漂移。
 *
 * <p>注意: Java annotation 的 String 值必须 compile-time constant, 所以这里只是
 * String literal holder, 不能用 String.format() 之类运行时拼接。
 * 真正的"运行时 helper"(如 IdempotencyKey.byUserAndOrderNo(op, orderNo))
 * 仍是 Phase 2 的工作 — 本类先立 seam。
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    /** 锁座幂等: userId + sessionId + seatIndexes(去重序列化)。
     *  P0 修复: 必须带 userId(否则 user1/user2 锁同一座位会撞同一个 key 互锁)。 */
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

    /**
     * 对话下单确认幂等(ADR-0004): 键就是 proposalId 本身。
     *
     * <p>刻意<b>不</b>用 {@code userId:sessionId:seatIndexes} —— 座位列表从不进幂等键,
     * 模型的幻觉或重试产生不了第二把钥匙(ADR-0002 证据 2)。
     */
    public static final String CHAT_ORDER_CONFIRM = "#proposalId";

    /**
     * 对话消息 rate limit: userId + ':chat'。
     *
     * <p><b>括号是必须的, 不是排版洁癖</b>: SpEL 里 {@code ?:} 的优先级<b>低于</b> {@code +},
     * 所以 {@code userId() ?: 'anon' + ':chat'} 会被解析成
     * {@code userId() ?: ('anon' + ':chat')} —— 登录用户拿到的桶 key 是 {@code 42}
     * (没有 {@code :chat} 后缀), 与匿名桶 {@code anon:chat} 不对称。
     * 补上括号后两端一致: 登录 {@code 42:chat}, 匿名仍然是 {@code anon:chat}。
     *
     * <p>匿名桶的 key 与限流语义**逐字不变** —— 本次只补登录桶缺失的后缀。
     */
    public static final String RATE_CHAT_MESSAGE_BY_USER =
            "((T(com.cinema.common.context.UserContext).userId()) ?: 'anon') + ':chat'";

    /**
     * 对话下单提案 rate limit: userId + ':chat:proposal'。
     *
     * <p><b>按用户分桶, 不按场次</b>: 原来把 sessionId 拼进桶键, 于是「10 次/分钟」
     * 实际是「每场次 10 次/分钟」, 换个场次就能再刷一轮 —— 限流形同虚设。
     * 口径对齐既有 {@code POST /api/chat/message} 的 userId 全局分桶。
     */
    public static final String RATE_CHAT_PROPOSAL_BY_USER =
            "(T(com.cinema.common.context.UserContext).userId() ?: 'anon') + ':chat:proposal'";

    /**
     * 对话下单确认 rate limit: userId + ':chat:confirm'。
     *
     * <p><b>不把 proposalId 拼进桶键</b>: 提案是一次性消费的, 每份提案 id 都唯一,
     * 拼进去之后「3 次/分钟」永远触发不了 —— 限流等于没写。
     */
    public static final String RATE_CHAT_CONFIRM_BY_USER =
            "(T(com.cinema.common.context.UserContext).userId() ?: 'anon') + ':chat:confirm'";
}
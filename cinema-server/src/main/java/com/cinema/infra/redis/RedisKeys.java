package com.cinema.infra.redis;

/**
 * Redis Key 常量与拼接规则 (设计见 docs/实现方案.md §4.3)
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 场次座位锁定位图(含已售) */
    public static String sessionLock(Long sessionId) {
        return "cinema:session:" + sessionId + ":lock";
    }

    /** 场次座位已售位图 */
    public static String sessionSold(Long sessionId) {
        return "cinema:session:" + sessionId + ":sold";
    }

    /**
     * 位图重建的**临时** lock key(2026-10-25)。
     *
     * <p>原子替换位图时, 位图先建在这个临时 key 上, 建好后再 RENAME 覆盖正式 key。
     * 正式位图在换名之前原封不动 —— 重建失败时座位保持「已锁/已售」(宁可锁死也不误放),
     * 不会出现「位图被清空 → 全场可选 → 超卖」的窗口。
     *
     * <p>用完即随 RENAME 消失; 重建失败时由调用方清理残留。
     */
    public static String sessionLockRebuild(Long sessionId) {
        return sessionLock(sessionId) + ":rebuild";
    }

    /**
     * 位图重建的**临时** lock 快照 key(2026-10-25)。见 {@link #sessionLockRebuild(Long)}。
     */
    public static String sessionSoldRebuild(Long sessionId) {
        return sessionSold(sessionId) + ":rebuild";
    }

    /**
     * 位图重建的**合并结果** lock key(2026-10-25 二次评审修复)。
     *
     * <p>重建语义是「正式位图 OR DB 快照」(只加不减),BITOP 的 dest 不能与源相同,
     * 所以合并结果要落在第三个临时 key 上, 再 RENAME 覆盖正式 key。
     */
    public static String sessionLockMerged(Long sessionId) {
        return sessionLock(sessionId) + ":merged";
    }

    /**
     * 位图重建的**合并结果** sold key(2026-10-25 二次评审修复)。见 {@link #sessionLockMerged(Long)}。
     */
    public static String sessionSoldMerged(Long sessionId) {
        return sessionSold(sessionId) + ":merged";
    }

    /** 场次信息缓存 Hash */
    public static String sessionInfo(Long sessionId) {
        return "cinema:session:info:" + sessionId;
    }

    /** 影厅座位布局 JSON */
    public static String hallLayout(Long hallId) {
        return "cinema:hall:layout:" + hallId;
    }

    /**
     * 用户在某场次的待支付订单号 (一场一单)。
     *
     * <p><b>只写镜像</b> —— 这个 key 只被写、从不被读。锁座时写一份(TTL = 支付窗口),
     * 关单 / 支付时删掉。但「用户在这场次有没有待支付单」这个判断,
     * 一律以 DB 为权威源(见 {@code PendingOrderQuery}), 原因有二:
     * <ul>
     *   <li>Redis 与 DB 之间没有事务, 二者可能不一致; 以 DB 为准才不会漏判。</li>
     *   <li>DB 上有唯一索引 {@code uk_user_pending(user_id, pending_key)} 兜底,
     *       查出来就一定只有一笔, 不存在「Redis 记了两笔」的情形。</li>
     * </ul>
     * 保留这个 key 是为了兼容既有 Redis 数据与排查工具, 不要把它当查询源用。
     */
    public static String userPending(Long userId, Long sessionId) {
        return "cinema:user:pending:" + userId + ":" + sessionId;
    }

    /** 延迟关单 ZSet (member=orderNo, score=过期时间戳毫秒) */
    public static final String ORDER_TIMEOUT_ZSET = "cinema:order:timeout";

    /** 座位变更事件 Pub/Sub 频道 */
    public static final String SEAT_EVENT_CHANNEL = "seat:event";

    /**
     * 座位变更事件的序号计数器(全局单调自增, <b>不设 TTL</b>)。
     *
     * <p><b>为什么是全局而不是按场次</b>: 按场次计的计数器需要生命周期管理(场次结束后清理)。
     * 而一旦计数器过期归零, 客户端仍持有的「该座位已应用序号」仍是旧的大数, 此后所有新事件都会被
     * 判为陈旧而丢弃, 座位图静默失效且无任何报错。全局单调计数器永不回绕, 该失效模式不存在,
     * 也不需要任何清理逻辑 —— 它是全局单 key, 数量不随场次增长。
     *
     * <p><b>自增失败时前端必须把 seq=0 当作「无序号」</b>(总是应用), 不能当作「比任何已应用
     * 序号都旧」。见前端 filterBySeq 的实现。
     */
    public static final String SEAT_EVENT_SEQ = "cinema:seat:event:seq";

    /**
     * 用户在某场次锁定的座位集合 Hash(Phase A-① 新增)
     * field = seatIndex, value = orderNo; 锁座时 HMSET, 释放/支付时 HDEL。
     * 命中即跳过 OrderService.lockSeats 中的 "查 user 待支付单" DB 调用。
     */
    public static String userLocked(Long userId, Long sessionId) {
        return "cinema:user:locked:" + userId + ":" + sessionId;
    }

    /**
     * 退票座位释放的**按订单幂等标记**(2026-10-24)。
     *
     * <p><b>为什么需要它</b>: 退票主流程「CAS PAID→REFUNDING → 释放座位 → 调渠道退款 →
     * CAS REFUNDING→REFUNDED」在释放座位之后还有几步。若中途中断(进程被杀 / DB 写入异常),
     * 订单会停在 {@code REFUNDING}, 而座位**已经被释放回可选池** ——
     * 补偿任务 5 分钟后会再调一次退票释放通道。此时该座位若已被他人买走(sold=1),
     * 第二次释放会把**别人已付款的座位**清成可选 → 同一座位两张 PAID 订单 = 超卖。
     *
     * <p>位图里没有「这张 sold 位属于哪张单」的信息, 光看位图判断不了归属。
     * 所以释放动作本身必须**按订单幂等**: 标记与清位在同一个 Lua 里原子完成,
     * 于是「本单座位已释放过」这件事被持久化下来, 补偿任务第二次调用时直接跳过 ——
     * 无论那批座位此刻归谁, 都不会再被清一次。
     *
     * <p>TTL 取 {@link #SEAT_RELEASED_TTL_SECONDS}: 覆盖「支付窗口 900s + 补偿任务 5min」
     * 的量级, 超时后标记消失, 理论上允许再释放一次 —— 但那时订单早已终结, 补偿任务不会再扫到它。
     */
    public static String orderSeatReleased(String orderNo) {
        return "cinema:order:seat-released:" + orderNo;
    }

    /** 退票释放幂等标记的存活时长(秒)。 */
    public static final long SEAT_RELEASED_TTL_SECONDS = 86_400L;

    /**
     * 对话助手的下单提案快照(ADR-0004)。
     *
     * <p><b>提案不占位</b>:这里存的只是一份「已算好的报价与座位方案」,
     * TTL(默认 90s)内座位<b>不保证可用</b>。真正占位只发生在
     * {@code OrderLockService#lockSeats} 那一刻。
     *
     * <p>确认时用 {@code proposalId} 作幂等键 —— 模型的座位列表从不进入幂等键,
     * 所以模型幻觉/重试产生不了第二把钥匙(ADR-0002 证据 2 的正面回应)。
     */
    public static String chatProposal(String proposalId) {
        return "cinema:chat:proposal:" + proposalId;
    }
}

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

    /** 场次信息缓存 Hash */
    public static String sessionInfo(Long sessionId) {
        return "cinema:session:info:" + sessionId;
    }

    /** 影厅座位布局 JSON */
    public static String hallLayout(Long hallId) {
        return "cinema:hall:layout:" + hallId;
    }

    /** 用户在某场次的待支付订单号 (一场一单) */
    public static String userPending(Long userId, Long sessionId) {
        return "cinema:user:pending:" + userId + ":" + sessionId;
    }

    /** 延迟关单 ZSet (member=orderNo, score=过期时间戳毫秒) */
    public static final String ORDER_TIMEOUT_ZSET = "cinema:order:timeout";

    /** 座位变更事件 Pub/Sub 频道 */
    public static final String SEAT_EVENT_CHANNEL = "seat:event";

    /**
     * 座位变更事件的序号计数器(全局单调自增, <b>不设 TTL</b>).
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
     * field = seatIndex, value = orderNo; 锁座时 HMSET, 释放/支付时 HDEL.
     * 命中即跳过 OrderService.lockSeats 中的 "查 user 待支付单" DB 调用.
     */
    public static String userLocked(Long userId, Long sessionId) {
        return "cinema:user:locked:" + userId + ":" + sessionId;
    }
}

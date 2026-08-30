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
}

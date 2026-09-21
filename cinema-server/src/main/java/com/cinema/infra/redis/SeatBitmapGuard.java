package com.cinema.infra.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * P5 冷启动守护: 服务重启 / Redis flush 后,锁座前若发现位图为空, 从 DB 重建.
 * <p>不持有任何缓存状态, 每次调用都重新检查. 无锁竞争风险:
 * <ul>
 *   <li>RECOVER_SCRIPT 用 SETBIT 原子设置, 与 lock_seat.lua 同 SETBIT 命令互不冲突</li>
 *   <li>两个恢复请求并发时只会重复设同样的 bit (1), 不影响最终状态</li>
 * </ul>
 *
 * <p>#2 收尾: 重建向量 (查 orders + items + lock/sold 分区) 的 28 行重复代码
 * 已抽到 {@link SeatBitmapRebuilder}. Guard 只保留"是否需要恢复"的策略
 * (EXISTS 短路 / 清空位图 / 返回码), Rebuilder 负责"事实" (分区规则).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatBitmapGuard {

    private final StringRedisTemplate redisTemplate;
    private final SeatBitmapRebuilder rebuilder;
    private final SeatLuaService seatLuaService;

    /**
     * 确保 sessionId 的位图存在. 若缺失且 DB 有有效订单, 触发恢复.
     * @return true 表示触发了恢复, false 表示无需恢复
     */
    public boolean ensureBitmaps(Long sessionId) {
        String lockKey = RedisKeys.sessionLock(sessionId);
        String soldKey = RedisKeys.sessionSold(sessionId);

        // 锁定位图存在 → 无需恢复
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            return false;
        }

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(sessionId);
        if (!v.hasOrders()) {
            // DB 也没数据, 位图就是空, 无需恢复
            return false;
        }

        long t0 = System.currentTimeMillis();
        RecoverResult result = seatLuaService.recoverSeats(lockKey, soldKey, v.lock(), v.sold());
        long cost = System.currentTimeMillis() - t0;
        log.info("[bitmap-recover] sid={} lock={} sold={} cost={}ms", sessionId, result.lock(), result.sold(), cost);
        return true;
    }

    /**
     * 强制恢复(管理端手动触发). 跳过 EXISTS 检查, 直接重建.
     */
    public RecoverResult forceRecover(Long sessionId) {
        String lockKey = RedisKeys.sessionLock(sessionId);
        String soldKey = RedisKeys.sessionSold(sessionId);

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(sessionId);
        if (!v.hasOrders()) {
            log.info("[bitmap-recover:force] sid={} 无有效订单, 清空位图", sessionId);
            redisTemplate.delete(lockKey);
            redisTemplate.delete(soldKey);
            return new RecoverResult(0, 0);
        }

        // 强制模式: 先清空再恢复
        redisTemplate.delete(lockKey);
        redisTemplate.delete(soldKey);
        RecoverResult result = seatLuaService.recoverSeats(lockKey, soldKey, v.lock(), v.sold());
        log.info("[bitmap-recover:force] sid={} lock={} sold={}", sessionId, result.lock(), result.sold());
        return result;
    }
}
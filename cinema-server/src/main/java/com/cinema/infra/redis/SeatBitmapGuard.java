package com.cinema.infra.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * P5 冷启动守护: 服务重启 / Redis flush 后,锁座前若发现位图为空, 从 DB 重建。
 * <p>不持有任何缓存状态, 每次调用都重新检查. 无锁竞争风险:
 * <ul>
 *   <li>RECOVER_SCRIPT 用 SETBIT 原子设置, 与 lock_seat.lua 同 SETBIT 命令互不冲突</li>
 *   <li>两个恢复请求并发时只会重复设同样的 bit (1), 不影响最终状态</li>
 * </ul>
 *
 * <p>#2 收尾: 重建向量 (查 orders + items + lock/sold 分区) 的 28 行重复代码
 * 已抽到 {@link SeatBitmapRebuilder}. Guard 只保留"是否需要恢复"的策略
 * (EXISTS 短路 / 清空位图 / 返回码), Rebuilder 负责"事实" (分区规则)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatBitmapGuard {

    private final StringRedisTemplate redisTemplate;
    private final SeatBitmapRebuilder rebuilder;
    private final SeatLuaService seatLuaService;

    /**
     * 确保 sessionId 的位图存在. 若缺失且 DB 有有效订单, 触发恢复。
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
        // 原子替换而不是「先删后建」: 冷启动期间正式位图可能已经存在一部分陈旧位,
        // 但删除与重建之间的空窗会让已付款的座位看起来可选 —— 宁可先多占着, 也不误放。
        RecoverResult result = seatLuaService.replaceSeats(sessionId, v.lock(), v.sold());
        long cost = System.currentTimeMillis() - t0;
        log.info("[bitmap-recover] sid={} lock={} sold={} cost={}ms", sessionId, result.lock(), result.sold(), cost);
        return true;
    }

    /**
     * 清空某场次的座位位图(锁定位 + 已售位)。
     *
     * <p>位图归本类所有 —— 管理端删除场次时原先在 controller 里直接
     * {@code redisTemplate.delete(RedisKeys.sessionLock(...))}, 键名与清空策略散在两处,
     * 现在统一从这里走。
     */
    public void clear(Long sessionId) {
        redisTemplate.delete(RedisKeys.sessionLock(sessionId));
        redisTemplate.delete(RedisKeys.sessionSold(sessionId));
    }

    /**
     * 强制恢复(管理端手动触发 / 支付位图写失败后的补偿)。跳过 EXISTS 检查, 直接重建。
     *
     * <p><b>2026-10-25 修复: 不再「先清空再恢复」</b>。
     *
     * <p>原先的写法是 {@code clear(sessionId)} 之后才 {@code recoverSeats(...)} ——
     * 两次 Redis 调用之间正式位图是空的, 此刻任何一次锁座都会看到「座位可选」→
     * 已付款的座位被再次卖出 = **超卖**; 更糟的是重建本身还可能失败(Redis 抖动 / OOM),
     * 失败后位图就永久停在「空」状态, 整场次座位全部看起来可选。
     *
     * <p>现在改成 {@link SeatLuaService#replaceSeats}: 位图先建在临时 key 上, 建好后再
     * 原子换名。重建失败 = 正式位图原封不动 = 座位保持「已锁/已售」(宁可锁死也不误放)。
     * 调用方 {@code OrderPayService} 的注释与本实现现在说的是同一件事。
     */
    public RecoverResult forceRecover(Long sessionId) {
        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(sessionId);
        if (!v.hasOrders()) {
            log.info("[bitmap-recover:force] sid={} 无有效订单, 清空位图", sessionId);
            clear(sessionId);
            return new RecoverResult(0, 0);
        }

        RecoverResult result = seatLuaService.replaceSeats(sessionId, v.lock(), v.sold());
        log.info("[bitmap-recover:force] sid={} lock={} sold={}", sessionId, result.lock(), result.sold());
        return result;
    }
}
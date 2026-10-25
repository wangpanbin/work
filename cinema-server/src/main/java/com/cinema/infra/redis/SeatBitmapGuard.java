package com.cinema.infra.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * P5 冷启动守护: 服务重启 / Redis flush 后,锁座前若发现位图为空, 从 DB 重建。
 * <p>不持有任何缓存状态, 每次调用都重新检查. 无锁竞争风险:
 * <ul>
 *   <li>合并走 BITOP OR + 原子 RENAME(见 {@link SeatLuaService#mergeSeats}),
 *       与 lock_seat.lua 的 SETBIT 互不冲突</li>
 *   <li>两个恢复请求并发时只会重复设同样的 bit (1), 不影响最终状态</li>
 * </ul>
 *
 * <p>#2 收尾: 重建向量 (查 orders + items + lock/sold 分区) 的 28 行重复代码
 * 已抽到 {@link SeatBitmapRebuilder}. Guard 只保留"是否需要恢复"的策略
 * (EXISTS 短路 / 合并位图 / 返回码), Rebuilder 负责"事实" (分区规则)。
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
        // 合并而不是替换: 正式位图 OR DB 快照(只加不减) —— 快照与换名之间新产生的锁
        // 不会被抹掉。详见 SeatLuaService#mergeSeats 的注释。
        RecoverResult result = seatLuaService.mergeSeats(sessionId, v.lock(), v.sold());
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
     * <p>现在改成 {@link SeatLuaService#mergeSeats}: <b>正式位图 OR DB 快照</b>, 只加不减。
     * 座位只可能变不可选, 绝不可能变可选(宁可锁死也不误放)。
     *
     * <p><b>2026-10-25 二次评审修复</b>: 旧实现是「用 DB 快照 RENAME 覆盖正式位图」。
     * 看着安全, 其实还有一道窗口 —— DB 快照是在脚本之外取的, 快照与换名之间有一次网络往返,
     * 那段时间里任何一次锁座都会把位写进正式位图, 而这份锁不在快照里 → 覆盖后被抹掉
     * → 座位回落为「可选」→ 同一座位两张待支付单 = <b>超卖</b>。冷启动路径尤其危险:
     * 位图刚被清掉、所有人同时抢座, 窗口内的每一把锁都会被抹掉。
     *
     * <p><b>代价(有意为之)</b>: 在线重建不再「清空」位图 —— 「位图有位而 DB 无单」是泄漏位,
     * 而清位会放走「已锁座但订单尚未落库」的锁。泄漏位与体积收缩交给离线工具
     * {@code tools/reconcile_seat_bitmap.py --fix}(显式带 {@code --fix} 开关的运维动作)。
     */
    public RecoverResult forceRecover(Long sessionId) {
        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(sessionId);
        if (!v.hasOrders()) {
            // DB 里没有有效订单 → 没有任何「该占着」的座位要补齐。
            // 刻意**不清空**位图: 那会把「已锁座但订单尚未落库」的锁放走(超卖),
            // 也无法区分泄漏位。泄漏位用 reconcile_seat_bitmap.py --fix 离线清。
            log.info("[bitmap-recover:force] sid={} DB 无有效订单, 不动位图(在线重建只补齐、不清位)", sessionId);
            return new RecoverResult(0, 0);
        }

        RecoverResult result = seatLuaService.mergeSeats(sessionId, v.lock(), v.sold());
        log.info("[bitmap-recover:force] sid={} 合并 lock={} sold={}", sessionId, result.lock(), result.sold());
        return result;
    }
}
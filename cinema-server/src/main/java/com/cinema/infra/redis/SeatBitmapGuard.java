package com.cinema.infra.redis;

import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P5 冷启动守护: 服务重启 / Redis flush 后,锁座前若发现位图为空, 从 DB 重建.
 * <p>不持有任何缓存状态, 每次调用都重新检查. 无锁竞争风险:
 * <ul>
 *   <li>RECOVER_SCRIPT 用 SETBIT 原子设置, 与 lock_seat.lua 同 SETBIT 命令互不冲突</li>
 *   <li>两个恢复请求并发时只会重复设同样的 bit (1), 不影响最终状态</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatBitmapGuard {

    private final StringRedisTemplate redisTemplate;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
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

        // 查 DB: 该场次所有未完结订单(status=0 待支付, status=1 已支付)
        List<Order> orders = orderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                        .eq(Order::getSessionId, sessionId)
                        .in(Order::getStatus, OrderStatus.PENDING_PAY.getCode(), OrderStatus.PAID.getCode()));
        if (orders.isEmpty()) {
            // DB 也没数据, 位图就是空, 无需恢复
            return false;
        }

        // 一次查 order_item 复用给两个 list
        Set<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toSet());
        List<OrderItem> items = orderItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderId, orderIds));

        // lock bitmap: status=0 + 1 全部
        List<Integer> lockSeats = items.stream().map(OrderItem::getSeatIndex).distinct().sorted().toList();
        // sold bitmap: 仅 status=1
        Set<Long> paidOrderIds = orders.stream()
                .filter(o -> o.getStatus() == OrderStatus.PAID.getCode())
                .map(Order::getId)
                .collect(Collectors.toSet());
        List<Integer> soldSeats = items.stream()
                .filter(it -> paidOrderIds.contains(it.getOrderId()))
                .map(OrderItem::getSeatIndex)
                .distinct()
                .sorted()
                .toList();

        long t0 = System.currentTimeMillis();
        RecoverResult result = seatLuaService.recoverSeats(lockKey, soldKey, lockSeats, soldSeats);
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

        List<Order> orders = orderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                        .eq(Order::getSessionId, sessionId)
                        .in(Order::getStatus, OrderStatus.PENDING_PAY.getCode(), OrderStatus.PAID.getCode()));
        if (orders.isEmpty()) {
            log.info("[bitmap-recover:force] sid={} 无有效订单, 清空位图", sessionId);
            redisTemplate.delete(lockKey);
            redisTemplate.delete(soldKey);
            return new RecoverResult(0, 0);
        }

        Set<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toSet());
        List<OrderItem> items = orderItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderId, orderIds));

        List<Integer> lockSeats = items.stream().map(OrderItem::getSeatIndex).distinct().sorted().toList();
        Set<Long> paidOrderIds = orders.stream()
                .filter(o -> o.getStatus() == OrderStatus.PAID.getCode())
                .map(Order::getId)
                .collect(Collectors.toSet());
        List<Integer> soldSeats = items.stream()
                .filter(it -> paidOrderIds.contains(it.getOrderId()))
                .map(OrderItem::getSeatIndex)
                .distinct()
                .sorted()
                .toList();

        // 强制模式: 先清空再恢复
        redisTemplate.delete(lockKey);
        redisTemplate.delete(soldKey);
        RecoverResult result = seatLuaService.recoverSeats(lockKey, soldKey, lockSeats, soldSeats);
        log.info("[bitmap-recover:force] sid={} lock={} sold={}", sessionId, result.lock(), result.sold());
        return result;
    }
}

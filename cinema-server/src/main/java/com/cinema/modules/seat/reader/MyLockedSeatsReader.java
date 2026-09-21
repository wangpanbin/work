package com.cinema.modules.seat.reader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 用户在场次的"我锁的座位"读侧: Redis Hash 优先 → miss 回源 DB 并回填.
 *
 * <p>#4 收尾: 抽自 SeatService.loadMyLockedSeats. Hash field=seatIndex, value=orderNo.
 * 命中即返回 field 列表; 未命中查 DB 并回填(便于锁座前已存在但缓存被清的情况).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MyLockedSeatsReader {

    private final StringRedisTemplate redisTemplate;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    public List<Integer> load(Long userId, Long sessionId) {
        if (userId == null) {
            return List.of();
        }
        String key = RedisKeys.userLocked(userId, sessionId);
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries != null && !entries.isEmpty()) {
                return entries.keySet().stream()
                        .map(Object::toString)
                        .map(Integer::parseInt)
                        .sorted()
                        .toList();
            }
        }
        // 回源: 查订单 + 订单座位
        Order pending = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getSessionId, sessionId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .last("LIMIT 1"));
        if (pending == null) {
            return List.of();
        }
        List<Integer> seatIndexes = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, pending.getId()))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
        // 回填 Redis(便于后续请求命中)
        if (!seatIndexes.isEmpty()) {
            try {
                var hashOps = redisTemplate.opsForHash();
                for (Integer idx : seatIndexes) {
                    hashOps.put(key, String.valueOf(idx), pending.getOrderNo());
                }
                redisTemplate.expire(key, Duration.ofMillis(15 * 60 * 1000L));
            } catch (Exception ignored) {
                // 回填失败不影响主流程
            }
        }
        return seatIndexes;
    }
}
package com.cinema.modules.order.service.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单核心公共逻辑 — CAS 校验 / 订单归属 / 座位索引读取 / Redis user:locked 清理.
 * <p>E1 拆分: 从 OrderService 抽出共享代码,各 service 注入使用.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCore {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SessionMapper sessionMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 校验订单属于本人 + 存在,否则抛 BizException
     */
    public Order getOwnedOrder(String orderNo, Long userId) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该订单", null);
        }
        return order;
    }

    /**
     * 取订单座位索引: 优先 Order.seatIndexCache, 缺失再查 order_item
     */
    public List<Integer> seatIndexesOf(Order order) {
        if (order.getSeatIndexCache() != null) {
            return order.getSeatIndexCache();
        }
        return seatIndexesOf(order.getId());
    }

    public List<Integer> seatIndexesOf(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
    }

    /**
     * 校验场次可售 + 未开场
     */
    public Session requirePurchasableSession(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("场次不存在");
        }
        if (session.getStatus() == null || session.getStatus() != 1) {
            throw new BizException("场次当前不可购票");
        }
        if (!session.getStartTime().isAfter(LocalDateTime.now())) {
            throw new BizException("场次已开场,无法购票");
        }
        return session;
    }

    /**
     * 校验订单处于待支付状态
     */
    public void requirePending(Order order) {
        if (order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            throw new BizException("当前状态不可取消");
        }
    }

    /**
     * 清理 user:locked 缓存,失败仅 log 不抛出
     */
    public void clearUserLockedHash(Long userId, Long sessionId) {
        try {
            redisTemplate.delete(RedisKeys.userLocked(userId, sessionId));
        } catch (Exception e) {
            log.warn("[关单/支付] 清理 user:locked 失败 uid={} sid={}", userId, sessionId, e);
        }
    }
}

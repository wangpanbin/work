package com.cinema.modules.order.service;

import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.payment.service.MockPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单支付 — OrderService 拆分的 4 个 service 之一.
 * <p>E1 拆分: 负责 CAS 推进 PENDING_PAY → PAID, 与锁座/取消解耦.
 * <p>退票(N1)将在此扩展: 新增 refund() 方法 + REFUNDING/REFUNDED 状态.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPayService {

    private final OrderMapper orderMapper;
    private final SeatLuaService seatLuaService;
    private final SeatEventPublisher seatEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final MockPaymentService mockPaymentService;
    private final OrderCore orderCore;

    /** 模拟支付 */
    public void pay(String orderNo, Long userId) {
        Order order = orderCore.getOwnedOrder(orderNo, userId);
        if (order.getStatus() == OrderStatus.PAID.getCode()) {
            throw new BizException("订单已支付,请勿重复操作");
        }
        if (order.getStatus() == OrderStatus.CANCELLED.getCode()) {
            throw new BizException("订单已取消,座位已释放");
        }
        if (order.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException("订单已超时,座位已释放");
        }

        mockPaymentService.mockPayChannel(orderNo);

        // ★ CAS: 待支付 → 已支付(与超时关单互斥, 防双花)
        int updated = orderMapper.casMarkPaid(orderNo);
        if (updated == 0) {
            throw new BizException("订单状态已变化(可能已超时),请刷新");
        }

        // 锁 → 售
        List<Integer> seats = orderCore.seatIndexesOf(order);
        seatLuaService.confirmSeats(RedisKeys.sessionLock(order.getSessionId()),
                RedisKeys.sessionSold(order.getSessionId()), seats);
        // 清理 user:pending 与 user:locked
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        orderCore.clearUserLockedHash(order.getUserId(), order.getSessionId());
        seatEventPublisher.publishSold(order.getSessionId(), seats);
        log.info("[支付] orderNo={} seats={} 支付成功", orderNo, seats);
    }
}

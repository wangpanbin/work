package com.cinema.modules.order.service;

import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.AdminEventPublisher;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 订单取消 / 超时关单 — OrderService 拆分的 4 个 service 之一.
 * <p>E1 拆分: 负责主动取消(cancel) + 扫描器/补偿触发的 closeIfUnpaid.
 * <p>复用 OrderCore 做状态校验和座位索引读取.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderMapper orderMapper;
    private final SeatLuaService seatLuaService;
    private final SeatEventPublisher seatEventPublisher;
    private final AdminEventPublisher adminEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final OrderCore orderCore;

    /** 主动取消 */
    public void cancel(String orderNo, Long userId) {
        Order order = orderCore.getOwnedOrder(orderNo, userId);
        orderCore.requirePending(order);
        closeOrder(order, "CANCEL");
    }

    /**
     * 幂等关单(供超时扫描/补偿任务复用): CAS(0→2) 成功才释放座位, 重复投递天然安全
     */
    public boolean closeIfUnpaid(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            return false;
        }
        // 一次查 order_item 复用给 closeOrder
        order.setSeatIndexCache(orderCore.seatIndexesOf(order.getId()));
        return closeOrder(order, "TIMEOUT");
    }

    private boolean closeOrder(Order order, String eventType) {
        int updated = orderMapper.casCancel(order.getOrderNo());
        if (updated == 0) {
            return false;
        }
        List<Integer> seats = orderCore.seatIndexesOf(order);
        List<Integer> released = seatLuaService.releaseSeats(
                RedisKeys.sessionLock(order.getSessionId()),
                RedisKeys.sessionSold(order.getSessionId()), seats);
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        orderCore.clearUserLockedHash(order.getUserId(), order.getSessionId());
        seatEventPublisher.publishReleased(order.getSessionId(), seats);
        // D1 大屏: 推一条关单事件(CANCEL 用户主动 / TIMEOUT 超时扫描)
        adminEventPublisher.publish(eventType, Map.of(
                "orderNo", order.getOrderNo(),
                "userId", order.getUserId(),
                "sessionId", order.getSessionId(),
                "seats", seats));
        log.info("[关单] orderNo={} type={} 释放座位 {}(实际释放 {})", order.getOrderNo(), eventType, seats, released);
        return true;
    }
}

package com.cinema.infra.redis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 重建场次座位向量 — 单一 seam, SeatBitmapGuard.ensureBitmaps / forceRecover 共用.
 *
 * <p>无状态, 纯函数: 给定 sessionId, 返回该场次所有 PENDING_PAY+PAID 订单的座位分布
 * (lock = 全部待付+已售索引; sold = 仅已售索引).
 *
 * <p>保留 {@code orderCount} 是为了让 caller 区分"无订单跳过"与"订单存在但 items 为空"
 * (后者是数据完整性问题, 当前实现仍会调 Lua 传空列表, 保留旧行为).
 *
 * @see SeatBitmapGuard
 */
@Component
@RequiredArgsConstructor
public class SeatBitmapRebuilder {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    /**
     * 收集场次座位向量.
     *
     * @param sessionId 场次 ID
     * @return {@link SeatVectors}: 含 orderCount / lock / sold 三个字段
     */
    public SeatVectors collectSeatVectors(Long sessionId) {
        List<Order> orders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getSessionId, sessionId)
                        .in(Order::getStatus,
                                OrderStatus.PENDING_PAY.getCode(),
                                OrderStatus.PAID.getCode()));
        if (orders.isEmpty()) {
            return new SeatVectors(0, List.of(), List.of());
        }
        Set<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toSet());
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds));
        List<Integer> lockSeats = items.stream()
                .map(OrderItem::getSeatIndex)
                .distinct()
                .sorted()
                .toList();
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
        return new SeatVectors(orders.size(), lockSeats, soldSeats);
    }

    /**
     * 场次座位向量.
     *
     * @param orderCount 该场次订单数(PENDING_PAY + PAID);0 表示无订单
     * @param lock       所有待付+已售订单的座位索引(已去重排序)
     * @param sold       仅已售订单的座位索引(已去重排序)
     */
    public record SeatVectors(int orderCount, List<Integer> lock, List<Integer> sold) {

        /** 是否有订单(用于 caller 决定是否调 Lua)。 */
        public boolean hasOrders() {
            return orderCount > 0;
        }
    }
}
package com.cinema.infra.redis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SeatBitmapRebuilder 单测 — 纯函数 seam, 锁/售分区契约.
 * 重点锁定: status 过滤 / paidOrderIds 判定 / dedup / sort.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatBitmapRebuilderTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;

    private SeatBitmapRebuilder rebuilder;

    @BeforeEach
    void setUp() {
        rebuilder = new SeatBitmapRebuilder(orderMapper, orderItemMapper);
    }

    @Test
    @DisplayName("空订单: orderCount=0, lock=[], sold=[]")
    void collect_returnsEmpty_whenNoOrders() {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(1L);

        assertThat(v.orderCount()).isEqualTo(0);
        assertThat(v.lock()).isEmpty();
        assertThat(v.sold()).isEmpty();
        assertThat(v.hasOrders()).isFalse();
    }

    @Test
    @DisplayName("仅 PENDING_PAY: orderCount=1, lock=[10,11], sold=[]")
    void collect_returnsLockOnly_whenAllPendingPay() {
        Order pending = order(100L, 1L, OrderStatus.PENDING_PAY.getCode());
        OrderItem i10 = item(100L, 10);
        OrderItem i11 = item(100L, 11);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(i10, i11));

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(1L);

        assertThat(v.orderCount()).isEqualTo(1);
        assertThat(v.lock()).containsExactly(10, 11);
        assertThat(v.sold()).isEmpty();
        assertThat(v.hasOrders()).isTrue();
    }

    @Test
    @DisplayName("仅 PAID: lock=[10,11], sold=[10,11](PAID 座位同时计入 lock 和 sold)")
    void collect_returnsSoldOnly_whenAllPaid() {
        Order paid = order(200L, 1L, OrderStatus.PAID.getCode());
        OrderItem i10 = item(200L, 10);
        OrderItem i11 = item(200L, 11);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(paid));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(i10, i11));

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(1L);

        assertThat(v.orderCount()).isEqualTo(1);
        assertThat(v.lock()).containsExactly(10, 11);
        assertThat(v.sold()).containsExactly(10, 11);
    }

    @Test
    @DisplayName("混合状态: PENDING_PAY [10] + PAID [20] → lock=[10,20], sold=[20]")
    void collect_returnsMixed_whenBothStatuses() {
        Order pending = order(100L, 1L, OrderStatus.PENDING_PAY.getCode());
        Order paid = order(200L, 1L, OrderStatus.PAID.getCode());
        OrderItem i10 = item(100L, 10);
        OrderItem i20 = item(200L, 20);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending, paid));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(i10, i20));

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(1L);

        assertThat(v.orderCount()).isEqualTo(2);
        assertThat(v.lock()).containsExactly(10, 20);
        assertThat(v.sold()).containsExactly(20);
    }

    @Test
    @DisplayName("多 item 聚合: 单 PENDING_PAY 订单 3 个座位, 全部出现且排序")
    void collect_handlesMultipleItemsPerOrder() {
        Order pending = order(100L, 1L, OrderStatus.PENDING_PAY.getCode());
        OrderItem i30 = item(100L, 30);
        OrderItem i10 = item(100L, 10);
        OrderItem i20 = item(100L, 20);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(i30, i10, i20));

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(1L);

        assertThat(v.lock()).containsExactly(10, 20, 30);
    }

    @Test
    @DisplayName("同 seatIndex 去重: 防御性, 重复 seatIndex 只出现一次")
    void collect_dedupsSeatIndexes() {
        Order pending = order(100L, 1L, OrderStatus.PENDING_PAY.getCode());
        OrderItem i10a = item(100L, 10);
        OrderItem i10b = item(100L, 10);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pending));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(i10a, i10b));

        SeatBitmapRebuilder.SeatVectors v = rebuilder.collectSeatVectors(1L);

        assertThat(v.lock()).containsExactly(10);
        assertThat(v.lock()).hasSize(1);
    }

    // ---------- fixtures ----------

    private static Order order(Long id, Long sessionId, int status) {
        Order o = new Order();
        o.setId(id);
        o.setSessionId(sessionId);
        o.setStatus(status);
        return o;
    }

    private static OrderItem item(Long orderId, int seatIndex) {
        OrderItem i = new OrderItem();
        i.setOrderId(orderId);
        i.setSeatIndex(seatIndex);
        return i;
    }
}
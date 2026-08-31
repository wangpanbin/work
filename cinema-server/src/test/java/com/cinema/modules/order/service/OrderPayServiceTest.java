package com.cinema.modules.order.service;

import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.mapper.RefundLogMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.payment.service.MockPaymentService;
import com.cinema.modules.payment.service.MockRefundService;
import com.cinema.modules.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OrderPayService 单测 — 支付 2 个分支. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPayServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private RefundLogMapper refundLogMapper;
    @Mock private SeatLuaService seatLuaService;
    @Mock private SeatEventPublisher seatEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private MockPaymentService mockPaymentService;
    @Mock private MockRefundService mockRefundService;
    @Mock private SessionMapper sessionMapper;
    @Mock private OrderCore orderCore;
    @Mock private TicketService ticketService;

    private OrderPayService service;

    @BeforeEach
    void setUp() {
        service = new OrderPayService(orderMapper, refundLogMapper, seatLuaService,
                seatEventPublisher, redisTemplate, mockPaymentService, mockRefundService,
                sessionMapper, orderCore, ticketService);
    }

    @Test
    @DisplayName("支付成功: CAS 成功 → confirmSeats → 清 user:pending → 广播 SOLD")
    void pay_success() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10, 11));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(1);
        when(seatLuaService.confirmSeats(anyString(), anyString(), anyList())).thenReturn(List.of(10, 11));

        service.pay(orderNo, userId);

        verify(mockPaymentService).mockPayChannel(orderNo);
        verify(orderMapper).casMarkPaid(orderNo);
        verify(seatLuaService).confirmSeats(
                eq(RedisKeys.sessionLock(1L)), eq(RedisKeys.sessionSold(1L)), eq(List.of(10, 11)));
        verify(redisTemplate).delete(RedisKeys.userPending(userId, 1L));
        verify(seatEventPublisher).publishSold(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("支付失败: CAS 返回 0, 抛 BizException 不 confirmSeats")
    void pay_casFailed() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(0);

        assertThatThrownBy(() -> service.pay(orderNo, userId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态已变化");

        verify(seatLuaService, never()).confirmSeats(anyString(), anyString(), anyList());
        verify(seatEventPublisher, never()).publishSold(anyLong(), anyList());
    }

    private Order pendingOrder(String orderNo, Long userId, Long sessionId, List<Integer> seats) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(orderNo);
        o.setUserId(userId);
        o.setSessionId(sessionId);
        o.setStatus(OrderStatus.PENDING_PAY.getCode());
        o.setTotalAmount(new BigDecimal("39.90").multiply(BigDecimal.valueOf(seats.size())));
        o.setSeatCount(seats.size());
        o.setExpireAt(LocalDateTime.now().plusMinutes(15));
        o.setCreatedAt(LocalDateTime.now());
        o.setSeatIndexCache(seats);
        return o;
    }
}

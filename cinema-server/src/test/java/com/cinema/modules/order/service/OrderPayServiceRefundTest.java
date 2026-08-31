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
import com.cinema.modules.session.entity.Session;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OrderPayService.refund 单测 — N1 退票流程 3 个分支. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPayServiceRefundTest {

    @Mock private OrderMapper orderMapper;
    @Mock private RefundLogMapper refundLogMapper;
    @Mock private SeatLuaService seatLuaService;
    @Mock private SeatEventPublisher seatEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private MockPaymentService mockPaymentService;
    @Mock private MockRefundService mockRefundService;
    @Mock private SessionMapper sessionMapper;
    @Mock private OrderCore orderCore;

    private OrderPayService service;

    @BeforeEach
    void setUp() {
        service = new OrderPayService(orderMapper, refundLogMapper, seatLuaService,
                seatEventPublisher, redisTemplate, mockPaymentService, mockRefundService,
                sessionMapper, orderCore);
    }

    @Test
    @DisplayName("退票成功: CAS REFUNDING→CAS REFUNDED→release→refund_log→广播")
    void refund_success() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = paidOrder(orderNo, userId, 1L, List.of(10, 11));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));
        Session session = newSession(1L, LocalDateTime.now().plusHours(3));
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(orderMapper.casMarkRefunding(orderNo)).thenReturn(1);
        when(orderMapper.casMarkRefunded(orderNo)).thenReturn(1);
        when(seatLuaService.releaseSeats(anyString(), anyString(), anyList())).thenReturn(List.of(10, 11));

        service.refund(orderNo, userId);

        verify(orderMapper).casMarkRefunding(orderNo);
        verify(seatLuaService).releaseSeats(
                eq(RedisKeys.sessionLock(1L)), eq(RedisKeys.sessionSold(1L)), eq(List.of(10, 11)));
        verify(mockRefundService).mockRefundChannel(eq(orderNo), any(BigDecimal.class));
        verify(refundLogMapper).insert(any(com.cinema.modules.order.entity.RefundLog.class));
        verify(orderMapper).casMarkRefunded(orderNo);
        verify(seatEventPublisher).publishReleased(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("退票拒绝: 订单非 PAID 状态, 抛 BizException")
    void refund_wrongStatus() {
        Order order = paidOrder("ORD-1", 100L, 1L, List.of(10));
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        when(orderCore.getOwnedOrder("ORD-1", 100L)).thenReturn(order);

        assertThatThrownBy(() -> service.refund("ORD-1", 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅已支付订单可退票");

        verify(orderMapper, never()).casMarkRefunding(anyString());
    }

    @Test
    @DisplayName("退票拒绝: 场次已开场, 抛 BizException")
    void refund_sessionStarted() {
        Order order = paidOrder("ORD-1", 100L, 1L, List.of(10));
        when(orderCore.getOwnedOrder("ORD-1", 100L)).thenReturn(order);
        when(sessionMapper.selectById(1L))
                .thenReturn(newSession(1L, LocalDateTime.now().minusMinutes(10)));

        assertThatThrownBy(() -> service.refund("ORD-1", 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("场次已开场");

        verify(orderMapper, never()).casMarkRefunding(anyString());
    }

    @Test
    @DisplayName("退票拒绝: CAS PAID→REFUNDING 失败(并发/已被退), 抛 BizException")
    void refund_casFailed() {
        Order order = paidOrder("ORD-1", 100L, 1L, List.of(10));
        when(orderCore.getOwnedOrder("ORD-1", 100L)).thenReturn(order);
        when(sessionMapper.selectById(1L))
                .thenReturn(newSession(1L, LocalDateTime.now().plusHours(3)));
        when(orderMapper.casMarkRefunding("ORD-1")).thenReturn(0);

        assertThatThrownBy(() -> service.refund("ORD-1", 100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单状态已变化");

        verify(mockRefundService, never()).mockRefundChannel(anyString(), any());
    }

    private Session newSession(Long id, LocalDateTime start) {
        Session s = new Session();
        s.setId(id);
        s.setMovieId(1L);
        s.setHallId(1L);
        s.setStartTime(start);
        s.setEndTime(start.plusHours(2));
        s.setPrice(new BigDecimal("39.90"));
        s.setStatus(1);
        return s;
    }

    private Order paidOrder(String orderNo, Long userId, Long sessionId, List<Integer> seats) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(orderNo);
        o.setUserId(userId);
        o.setSessionId(sessionId);
        o.setStatus(OrderStatus.PAID.getCode());
        o.setTotalAmount(new BigDecimal("39.90").multiply(BigDecimal.valueOf(seats.size())));
        o.setSeatCount(seats.size());
        o.setPaidAt(LocalDateTime.now());
        o.setCreatedAt(LocalDateTime.now());
        o.setSeatIndexCache(seats);
        return o;
    }
}

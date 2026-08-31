package com.cinema.modules.order.service;

import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.AdminEventPublisher;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OrderCancelService 单测 — 主动取消 + 超时关单 3 个分支. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCancelServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private SeatLuaService seatLuaService;
    @Mock private SeatEventPublisher seatEventPublisher;
    @Mock private AdminEventPublisher adminEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private OrderCore orderCore;

    private OrderCancelService service;

    @BeforeEach
    void setUp() {
        service = new OrderCancelService(orderMapper, seatLuaService, seatEventPublisher,
                adminEventPublisher, redisTemplate, orderCore);
    }

    @Test
    @DisplayName("主动取消: 状态 = PENDING_PAY 时, CAS → releaseSeats → 广播 RELEASED")
    void cancel_success() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10, 11));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));
        when(orderMapper.casCancel(orderNo)).thenReturn(1);
        when(seatLuaService.releaseSeats(anyString(), anyString(), anyList())).thenReturn(List.of(10, 11));

        service.cancel(orderNo, userId);

        verify(orderMapper).casCancel(orderNo);
        verify(seatLuaService).releaseSeats(
                eq(RedisKeys.sessionLock(1L)), eq(RedisKeys.sessionSold(1L)), eq(List.of(10, 11)));
        verify(seatEventPublisher).publishReleased(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("关单幂等: 状态非 PENDING_PAY 时 closeIfUnpaid 立即返回 false")
    void closeIfUnpaid_idempotent() {
        Order order = pendingOrder("ORD-1", 100L, 1L, List.of(10));
        order.setStatus(OrderStatus.PAID.getCode());
        when(orderMapper.selectByOrderNo("ORD-1")).thenReturn(order);

        boolean result = service.closeIfUnpaid("ORD-1");

        assertThat(result).isFalse();
        verify(orderMapper, never()).casCancel(anyString());
        verify(seatLuaService, never()).releaseSeats(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("关单成功: 状态 PENDING_PAY, CAS 成功 → release → 广播")
    void closeIfUnpaid_success() {
        Order order = pendingOrder("ORD-1", 100L, 1L, List.of(10));
        when(orderMapper.selectByOrderNo("ORD-1")).thenReturn(order);
        when(orderMapper.casCancel("ORD-1")).thenReturn(1);
        when(orderCore.seatIndexesOf(order.getId())).thenReturn(List.of(10));
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10));
        when(seatLuaService.releaseSeats(anyString(), anyString(), anyList())).thenReturn(List.of(10));

        boolean result = service.closeIfUnpaid("ORD-1");

        assertThat(result).isTrue();
        verify(seatEventPublisher).publishReleased(eq(1L), eq(List.of(10)));
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

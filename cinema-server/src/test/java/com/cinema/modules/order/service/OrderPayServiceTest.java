package com.cinema.modules.order.service;

import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.AdminEventPublisher;
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

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OrderPayService 单测 — 支付 2 个分支。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPayServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private RefundLogMapper refundLogMapper;
    @Mock private SeatLuaService seatLuaService;
    @Mock private SeatEventPublisher seatEventPublisher;
    @Mock private AdminEventPublisher adminEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private MockPaymentService mockPaymentService;
    @Mock private MockRefundService mockRefundService;
    @Mock private SessionMapper sessionMapper;
    @Mock private OrderCore orderCore;
    @Mock private TicketService ticketService;
    @Mock private SeatBitmapGuard seatBitmapGuard;

    private OrderPayService service;

    @BeforeEach
    void setUp() {
        service = new OrderPayService(orderMapper, refundLogMapper, seatLuaService,
                seatEventPublisher, adminEventPublisher, redisTemplate, mockPaymentService,
                mockRefundService, sessionMapper, orderCore, ticketService, seatBitmapGuard);
    }

    @Test
    @DisplayName("支付成功: CAS 成功 → confirmPaid → 清 user:pending → 广播 SOLD")
    void pay_success() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10, 11));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(1);
        when(seatLuaService.confirmPaid(anyLong(), anyList())).thenReturn(List.of(10, 11));

        service.pay(orderNo, userId);

        verify(mockPaymentService).mockPayChannel(orderNo);
        verify(orderMapper).casMarkPaid(orderNo);
        verify(seatLuaService).confirmPaid(eq(1L), eq(List.of(10, 11)));
        verify(redisTemplate).delete(RedisKeys.userPending(userId, 1L));
        verify(seatEventPublisher).publishSold(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("支付失败: CAS 返回 0, 抛 BizException 不 confirmPaid")
    void pay_casFailed() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(0);

        assertThatThrownBy(() -> service.pay(orderNo, userId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态已变化");

        verify(seatLuaService, never()).confirmPaid(anyLong(), anyList());
        verify(seatEventPublisher, never()).publishSold(anyLong(), anyList());
    }

    /**
     * 2026-10-24: 位图置售失败后的兜底。
     *
     * <p>支付是「先 CAS 改订单状态, 再写位图」两步。CAS 成功后 Lua 抛错 →
     * 订单 PAID 而位图停在 lock=1/sold=0 → 座位对所有人永久显示「被锁」,
     * 而超时扫描器与补偿任务只捞 status=0, 没人会再碰这笔单。
     *
     * <p>兜底: 从 DB 重建该场次位图。订单已是 PAID, 重建出来的 sold 位会把它正确置上。
     */
    @Test
    @DisplayName("位图置售失败: 不抛异常给用户, 改为从 DB 重建该场次位图")
    void pay_bitmapWriteFails_rebuildsBitmapFromDb() {
        Long userId = 100L;
        String orderNo = "ORD-BITMAP";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10, 11));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(1);
        when(seatLuaService.confirmPaid(anyLong(), anyList()))
                .thenThrow(new RuntimeException("Redis 连接中断"));

        // 支付已经成功(CAS 已过), 不应把异常抛给用户
        assertThatNoException().isThrownBy(() -> service.pay(orderNo, userId));

        // 兜底: 从 DB 重建位图, 把这笔已付订单的座位正确置为 sold
        verify(seatBitmapGuard).forceRecover(1L);
        verify(seatEventPublisher).publishSold(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("位图置售失败且重建也失败: 不抛给用户, 且调用方自己不删位图")
    void pay_bitmapWriteFails_rebuildAlsoFails_doesNotThrow() {
        Long userId = 100L;
        String orderNo = "ORD-BITMAP-2";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10));
        when(orderCore.getOwnedOrder(orderNo, userId)).thenReturn(order);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10));
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(1);
        when(seatLuaService.confirmPaid(anyLong(), anyList()))
                .thenThrow(new RuntimeException("Redis 连接中断"));
        doThrow(new RuntimeException("Redis 仍然不可用")).when(seatBitmapGuard).forceRecover(1L);
        assertThatNoException().isThrownBy(() -> service.pay(orderNo, userId));

        // 本用例只锁「调用方不自己删位图」这一层。
        // 「重建失败时位图本身不被清空」那条保证落在 SeatBitmapGuardTest#forceRecover_rebuildFails_keepsBitmap
        // 与 SeatLuaServiceBitmapRoundTripTest#replaceSeats_scriptFails_keepsRealBitmapKeys ——
        // 这里的 guard 是 mock, 断言不到真实实现。
        verify(seatBitmapGuard, never()).clear(anyLong());
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

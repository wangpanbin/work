package com.cinema.job;

import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.redis.SeatWrite;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.OrderCancelService;
import com.cinema.modules.order.service.core.OrderCore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OrderTimeoutCompensateJob} 单元测试。
 *
 * <p>锁住 F5 的核心契约: <b>卡死退款单降级时必须释放座位</b>。
 * 退款中的座位是已售状态, 只把订单改成「已取消」而不清位图, 座位就永远占着 ——
 * 订单已死, 再没有流程会碰它(联调报告 F5)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderTimeoutCompensateJobTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderCancelService orderCancelService;
    @Mock private OrderCore orderCore;
    @Mock private SeatLuaService seatLuaService;
    @Mock private SeatEventPublisher seatEventPublisher;

    private OrderTimeoutCompensateJob job;

    @BeforeEach
    void setUp() {
        job = new OrderTimeoutCompensateJob(orderMapper, orderCancelService, orderCore,
                seatLuaService, seatEventPublisher);
    }

    private Order stuckRefund(String orderNo, Long sessionId) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(orderNo);
        o.setUserId(100L);
        o.setSessionId(sessionId);
        return o;
    }

    @Test
    @DisplayName("卡死退款单: 释放座位(退票通道) → CAS 降级为已取消 → 发 RELEASED")
    void compensateStuckRefund_releasesSeats() {
        Order order = stuckRefund("ORD-R1", 42L);
        when(orderMapper.selectStuckRefunding()).thenReturn(List.of(order));
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));
        when(orderMapper.casStuckRefundingAsCancelled("ORD-R1")).thenReturn(1);

        job.compensateStuckRefund();

        ArgumentCaptor<SeatWrite.Paid> captor = ArgumentCaptor.forClass(SeatWrite.Paid.class);
        verify(seatLuaService).refundSold(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(42L);
        assertThat(captor.getValue().seats()).containsExactly(10, 11);

        verify(orderMapper).casStuckRefundingAsCancelled("ORD-R1");
        verify(seatEventPublisher).publishReleased(42L, List.of(10, 11));
    }

    @Test
    @DisplayName("没有卡死退款单: 不碰座位也不改状态")
    void compensateStuckRefund_noopWhenNothingStuck() {
        when(orderMapper.selectStuckRefunding()).thenReturn(List.of());

        job.compensateStuckRefund();

        verify(seatLuaService, never()).refundSold(any(SeatWrite.Paid.class));
        verify(orderMapper, never()).casStuckRefundingAsCancelled(any());
    }

    @Test
    @DisplayName("CAS 失败(退票流程已推到 REFUNDED): 不发事件, 也不抛异常")
    void compensateStuckRefund_casLostIsNotAFailure() {
        Order order = stuckRefund("ORD-R2", 42L);
        when(orderMapper.selectStuckRefunding()).thenReturn(List.of(order));
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10));
        when(orderMapper.casStuckRefundingAsCancelled("ORD-R2")).thenReturn(0);

        job.compensateStuckRefund();

        // 座位照常释放(幂等), 但状态没推进就不发事件
        verify(seatLuaService).refundSold(any(SeatWrite.Paid.class));
        verify(seatEventPublisher, never()).publishReleased(any(), any());
    }
}

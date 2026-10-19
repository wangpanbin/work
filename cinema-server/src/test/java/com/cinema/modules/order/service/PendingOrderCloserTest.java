package com.cinema.modules.order.service;

import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.redis.SeatWrite;
import com.cinema.infra.ws.AdminEventPublisher;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.CloseReason;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PendingOrderCloser} 单元测试 —— 关单协议的唯一实现。
 *
 * <p>锁住四件事:
 * <ol>
 *   <li>CAS 失败就不做任何副作用(并发重复投递天然安全)</li>
 *   <li>座位释放走「未支付」通道, 绝不用退票通道</li>
 *   <li>CANCEL / TIMEOUT 发管理端大屏事件, REPLACED 不发</li>
 *   <li>三种原因都要发座位释放事件(座位确实回到可选池)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingOrderCloserTest {

    @Mock private OrderMapper orderMapper;
    @Mock private SeatLuaService seatLuaService;
    @Mock private SeatEventPublisher seatEventPublisher;
    @Mock private AdminEventPublisher adminEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private OrderCore orderCore;

    private PendingOrderCloser closer;

    @BeforeEach
    void setUp() {
        closer = new PendingOrderCloser(orderMapper, seatLuaService, seatEventPublisher,
                adminEventPublisher, redisTemplate, orderCore);
    }

    private Order pendingOrder(String orderNo) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(orderNo);
        o.setUserId(100L);
        o.setSessionId(1L);
        o.setSeatIndexCache(List.of(10, 11));
        return o;
    }

    @Test
    @DisplayName("关单成功: CAS → 释放座位 → 清 user:pending → 清 user:locked → 发 RELEASED")
    void close_success() {
        Order order = pendingOrder("ORD-1");
        when(orderMapper.casCancel("ORD-1")).thenReturn(1);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));
        when(seatLuaService.releaseUnpaid(any(SeatWrite.Unpaid.class))).thenReturn(List.of(10, 11));

        boolean result = closer.close(order, CloseReason.CANCEL);

        assertThat(result).isTrue();
        verify(orderMapper).casCancel("ORD-1");
        verify(seatLuaService).releaseUnpaid(eq(new SeatWrite.Unpaid(1L, List.of(10, 11))));
        verify(redisTemplate).delete(RedisKeys.userPending(100L, 1L));
        verify(orderCore).clearUserLockedHash(100L, 1L);
        verify(seatEventPublisher).publishReleased(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("CAS 失败(订单已被别的流程改掉)→ 什么都不做, 返回 false")
    void close_casFailed_doesNothing() {
        Order order = pendingOrder("ORD-1");
        when(orderMapper.casCancel("ORD-1")).thenReturn(0);

        boolean result = closer.close(order, CloseReason.CANCEL);

        assertThat(result).isFalse();
        verify(seatLuaService, never()).releaseUnpaid(any(SeatWrite.Unpaid.class));
        verify(seatEventPublisher, never()).publishReleased(any(), anyList());
        verify(adminEventPublisher, never()).publish(anyString(), any());
    }

    @Test
    @DisplayName("关单走「未支付」通道 —— 绝不用退票通道(后者会连已售位一起清)")
    void close_usesUnpaidChannelNeverRefund() {
        Order order = pendingOrder("ORD-1");
        when(orderMapper.casCancel("ORD-1")).thenReturn(1);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));

        closer.close(order, CloseReason.CANCEL);

        verify(seatLuaService, never()).refundSold(any(SeatWrite.Paid.class));
    }

    @Test
    @DisplayName("CANCEL / TIMEOUT 发管理端大屏事件, type 与原因名逐字一致")
    void close_publishesAdminEventForCancelAndTimeout() {
        Order cancelOrder = pendingOrder("ORD-1");
        when(orderMapper.casCancel("ORD-1")).thenReturn(1);
        when(orderCore.seatIndexesOf(cancelOrder)).thenReturn(List.of(10, 11));
        closer.close(cancelOrder, CloseReason.CANCEL);
        verify(adminEventPublisher).publish(eq("CANCEL"), any());

        Order timeoutOrder = pendingOrder("ORD-2");
        when(orderMapper.casCancel("ORD-2")).thenReturn(1);
        when(orderCore.seatIndexesOf(timeoutOrder)).thenReturn(List.of(10, 11));
        closer.close(timeoutOrder, CloseReason.TIMEOUT);
        verify(adminEventPublisher).publish(eq("TIMEOUT"), any());
    }

    @Test
    @DisplayName("REPLACED 不发管理端大屏事件(顶替不是取消), 但座位释放事件照发")
    void close_replaced_doesNotPublishAdminEvent() {
        Order order = pendingOrder("ORD-1");
        when(orderMapper.casCancel("ORD-1")).thenReturn(1);
        when(orderCore.seatIndexesOf(order)).thenReturn(List.of(10, 11));

        boolean result = closer.close(order, CloseReason.REPLACED);

        assertThat(result).isTrue();
        verify(adminEventPublisher, never()).publish(anyString(), any());
        // 座位确实回到可选池 → 客户端仍要收到 RELEASED
        verify(seatEventPublisher).publishReleased(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("order 为 null → 直接返回 false, 不碰任何依赖")
    void close_nullOrder_returnsFalse() {
        assertThat(closer.close(null, CloseReason.CANCEL)).isFalse();
        verify(orderMapper, never()).casCancel(anyString());
    }
}

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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SeatBitmapGuard 单测 — 4 个分支. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatBitmapGuardTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private SeatLuaService seatLuaService;

    private SeatBitmapGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SeatBitmapGuard(redisTemplate, orderMapper, orderItemMapper, seatLuaService);
    }

    @Test
    @DisplayName("位图已存在: ensureBitmaps 直接返回 false, 不查 DB 不调 Lua")
    void ensure_bitmapsExist() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        boolean recovered = guard.ensureBitmaps(1L);

        assertThat(recovered).isFalse();
        verify(orderMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(seatLuaService, never()).recoverSeats(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("位图缺失且 DB 无订单: 直接返回 false, 不调 Lua(空场次不需要恢复)")
    void ensure_noOrders() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        boolean recovered = guard.ensureBitmaps(1L);

        assertThat(recovered).isFalse();
        verify(seatLuaService, never()).recoverSeats(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("位图缺失且 DB 有订单: 触发恢复, lock + sold bitmap 重建")
    void ensure_withOrders() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        // 2 个订单: 1 个待支付, 1 个已支付
        Order pending = order(1L, 1L, OrderStatus.PENDING_PAY.getCode());
        Order paid = order(2L, 1L, OrderStatus.PAID.getCode());
        when(orderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(pending, paid));

        // order_item: 座位 10,11 在 pending; 20 在 paid
        OrderItem i1 = item(1L, 1L, 10);
        OrderItem i2 = item(1L, 1L, 11);
        OrderItem i3 = item(2L, 1L, 20);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(i1, i2, i3));
        when(seatLuaService.recoverSeats(anyString(), anyString(), anyList(), anyList()))
                .thenReturn(new RecoverResult(3, 1));

        boolean recovered = guard.ensureBitmaps(1L);

        assertThat(recovered).isTrue();
        // lock bitmap 应包含 10, 11, 20; sold bitmap 应只含 20
        verify(seatLuaService).recoverSeats(
                eq(RedisKeys.sessionLock(1L)),
                eq(RedisKeys.sessionSold(1L)),
                eq(List.of(10, 11, 20)),
                eq(List.of(20)));
    }

    @Test
    @DisplayName("forceRecover: 先清空再重建, 用于管理端手动修复")
    void forceRecover() {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(order(1L, 1L, OrderStatus.PAID.getCode())));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(item(1L, 1L, 5)));
        when(seatLuaService.recoverSeats(anyString(), anyString(), anyList(), anyList()))
                .thenReturn(new RecoverResult(1, 1));

        RecoverResult result = guard.forceRecover(1L);

        assertThat(result.lock()).isEqualTo(1);
        assertThat(result.sold()).isEqualTo(1);
        verify(redisTemplate).delete(RedisKeys.sessionLock(1L));
        verify(redisTemplate).delete(RedisKeys.sessionSold(1L));
    }

    private Order order(Long id, Long sessionId, int status) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNo("ORD-" + id);
        o.setUserId(100L);
        o.setSessionId(sessionId);
        o.setStatus(status);
        o.setExpireAt(LocalDateTime.now().plusMinutes(15));
        o.setCreatedAt(LocalDateTime.now());
        return o;
    }

    private OrderItem item(Long orderId, Long sessionId, int seatIndex) {
        OrderItem i = new OrderItem();
        i.setOrderId(orderId);
        i.setSessionId(sessionId);
        i.setSeatIndex(seatIndex);
        return i;
    }
}

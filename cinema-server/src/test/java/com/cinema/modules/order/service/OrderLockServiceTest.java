package com.cinema.modules.order.service;

import com.cinema.common.exception.BizException;
import com.cinema.infra.delay.DelayQueue;
import com.cinema.infra.redis.LuaLockResult;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.order.vo.LockResultVO;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OrderLockService 单测 — 锁座下单 3 个分支. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderLockServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private SeatLuaService seatLuaService;
    @Mock private SeatBitmapGuard seatBitmapGuard;
    @Mock private DelayQueue delayQueue;
    @Mock private SeatEventPublisher seatEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private OrderCore orderCore;

    private OrderLockService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        service = new OrderLockService(orderMapper, orderItemMapper, sessionMapper,
                seatLuaService, seatBitmapGuard, delayQueue, seatEventPublisher, redisTemplate,
                transactionTemplate, orderCore);
    }

    @Test
    @DisplayName("锁座成功: 4 步链路全部触发, 返回 LockResultVO")
    void lockSeats_success() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10, 11));

        Session session = newSession(sessionId, LocalDateTime.now().plusHours(3));
        when(orderCore.requirePurchasableSession(sessionId)).thenReturn(session);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(seatLuaService.lockSeats(anyString(), anyString(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setOrderNo("ORD-1");
            return 1;
        });

        LockResultVO vo = service.lockSeats(userId, dto);

        assertThat(vo).isNotNull();
        assertThat(vo.getOrderNo()).isEqualTo("ORD-1");
        assertThat(vo.getSeatIndexes()).containsExactly(10, 11);
        verify(delayQueue).offer(eq("ORD-1"), eq(15L * 60 * 1000L));
        verify(valueOps).set(anyString(), eq("ORD-1"), any());
        verify(seatEventPublisher).publishLocked(eq(sessionId), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("锁座冲突: Lua 返回冲突, 抛 BizException, 不入队不广播")
    void lockSeats_conflict() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10, 11));

        Session session = newSession(sessionId, LocalDateTime.now().plusHours(3));
        when(orderCore.requirePurchasableSession(sessionId)).thenReturn(session);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(seatLuaService.lockSeats(anyString(), anyString(), anyList()))
                .thenReturn(new LuaLockResult(false, List.of(10)));

        assertThatThrownBy(() -> service.lockSeats(userId, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被占用");

        verify(orderMapper, never()).insert(any(Order.class));
        verify(delayQueue, never()).offer(anyString(), anyLong());
        verify(seatEventPublisher, never()).publishLocked(anyLong(), anyList());
    }

    @Test
    @DisplayName("锁座拒绝: 场次已开场, 抛 BizException 不进 Lua")
    void lockSeats_sessionStarted() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10));

        when(orderCore.requirePurchasableSession(sessionId))
                .thenThrow(new BizException("场次已开场,无法购票"));

        assertThatThrownBy(() -> service.lockSeats(userId, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已开场");

        verify(seatLuaService, never()).lockSeats(anyString(), anyString(), anyList());
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
}

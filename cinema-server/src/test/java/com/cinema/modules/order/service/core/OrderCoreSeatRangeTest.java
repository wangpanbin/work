package com.cinema.modules.order.service.core;

import com.cinema.common.exception.BizException;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.seat.SeatRangeGate;
import com.cinema.modules.seat.cache.SessionInfoCacheVO;
import com.cinema.modules.seat.reader.SessionInfoLoader;
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

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * P0 修复回归闸门(2026-10-11 稳定性测试): 座位索引范围闸门
 * ({@code SeatRangeGate#requireInRange}, 由 {@code OrderCore#requireSeatsInRange} 转发)。
 *
 * <p>这是本轮发现的 P0 缺陷的<b>权威校验点</b>。座位索引的上界取决于场次(1 号厅 96 座 /
 * 2 号厅 140 座), 无法用常量注解表达, 因此必须在服务层按 {@code seatCount} 判断。
 *
 * <p>缺少这道校验时越界索引会一路进到 {@code lock_seat.lua} 的 {@code SETBIT}, 而 Redis bitmap
 * 按 offset <b>立即分配内存且永不回收</b>(实测 offset=100000 → 位图 18B→12501B; 硬上界
 * 2^32-1 → 单 key 512MiB)。本测试锁死"越界一律 40001, 绝不进 Lua"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderCoreSeatRangeTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private SessionInfoLoader sessionInfoLoader;

    private OrderCore orderCore;

    @BeforeEach
    void setUp() {
        // 判定已抬到 SeatRangeGate, OrderCore 只做门面转发
        SeatRangeGate seatRangeGate = new SeatRangeGate(sessionInfoLoader);
        orderCore = new OrderCore(orderMapper, orderItemMapper, sessionMapper,
                redisTemplate, seatRangeGate);
    }

    private void givenSeatCount(int seatCount) {
        when(sessionInfoLoader.load(1L))
                .thenReturn(SessionInfoCacheVO.builder().sessionId(1L).seatCount(seatCount).build());
    }

    @Test
    @DisplayName("边界内: [0, seatCount-1] 首尾与中间值全部放行")
    void seatsInsideRange_pass() {
        givenSeatCount(140);
        assertThatCode(() -> orderCore.requireSeatsInRange(1L, List.of(0))).doesNotThrowAnyException();
        assertThatCode(() -> orderCore.requireSeatsInRange(1L, List.of(139))).doesNotThrowAnyException();
        assertThatCode(() -> orderCore.requireSeatsInRange(1L, List.of(0, 50, 139))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("P0 回归: seatIndex == seatCount(差一位越界)必须被拒, 且返回 40001")
    void seatEqualToSeatCount_rejected() {
        givenSeatCount(140);
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, List.of(140)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("座位索引越界")
                .hasFieldOrPropertyWithValue("code", 40001);
    }

    @Test
    @DisplayName("P0 回归: 大越界值(100000 / 4000000)必须被拒 —— 正是撑爆位图的那两个值")
    void largeOutOfRange_rejected() {
        givenSeatCount(140);
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, List.of(100000)))
                .isInstanceOf(BizException.class).hasMessageContaining("座位索引越界");
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, List.of(4000000)))
                .isInstanceOf(BizException.class).hasMessageContaining("座位索引越界");
    }

    @Test
    @DisplayName("P1 回归: 负数与 null 元素必须被拒(修复前落 50000「系统繁忙」)")
    void negativeOrNull_rejected() {
        givenSeatCount(140);
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, List.of(-1)))
                .isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", 40001);
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, List.of(-99999)))
                .isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", 40001);
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, Arrays.asList(10, null)))
                .isInstanceOf(BizException.class).hasFieldOrPropertyWithValue("code", 40001);
    }

    @Test
    @DisplayName("小影厅: 同一个 139 对 96 座厅越界、对 140 座厅合法 —— 上界必须按场次算")
    void rangeDependsOnSessionSeatCount() {
        givenSeatCount(96);
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, List.of(139)))
                .isInstanceOf(BizException.class).hasMessageContaining("座位索引越界");

        givenSeatCount(140);
        assertThatCode(() -> orderCore.requireSeatsInRange(1L, List.of(139))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("seatCount<=0(schema 漂移/影厅缺失)拒绝锁座, 不放行任何索引")
    void nonPositiveSeatCount_rejected() {
        givenSeatCount(0);
        assertThatThrownBy(() -> orderCore.requireSeatsInRange(1L, List.of(0)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("座位数异常")
                .hasFieldOrPropertyWithValue("code", 40001);
    }
}

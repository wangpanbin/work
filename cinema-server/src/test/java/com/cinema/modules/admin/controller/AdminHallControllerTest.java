package com.cinema.modules.admin.controller;

import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.hall.dto.HallDTO;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.seat.reader.HallLayoutCache;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminHallController} 单测 —— 2026-10-24 新增。
 *
 * <p>锁两条契约:
 * <ul>
 *   <li><b>改行列的防护</b>: 座位索引是 {@code seatIndex = (r-1)*cols + (c-1)},
 *       {@code cols} 一变同一个 {@code seat.id} 对应的 {@code seatIndex} 全变,
 *       已付费订单的 {@code order_item.seat_index} 与按场次的 Redis 位图(以 seatIndex 为下标)
 *       全部错位 → 已售座位变可选(超卖方向)+ 真实座位显示被占。所以有过订单就不许改行列。</li>
 *   <li><b>场次缓存失效</b>: 改影厅后必须清该厅名下场次的元数据缓存,
 *       否则 {@code GET /seat-map} 与 {@code SeatRangeGate} 会在 30 分钟内读到旧座位数。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminHallControllerTest {

    @Mock private HallMapper hallMapper;
    @Mock private SeatMapper seatMapper;
    @Mock private HallLayoutCache hallLayoutCache;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private SessionMapper sessionMapper;
    @Mock private OrderMapper orderMapper;
    @Mock private SessionInfoCacheService sessionInfoCacheService;

    @InjectMocks
    private AdminHallController controller;

    private static final Long HALL_ID = 1L;

    private Hall hall(int rows, int cols) {
        Hall h = new Hall();
        h.setId(HALL_ID);
        h.setCinemaId(1L);
        h.setName("1 号厅");
        h.setSeatRows(rows);
        h.setSeatCols(cols);
        h.setSeatCount(rows * cols);
        return h;
    }

    private HallDTO dto(Integer rows, Integer cols) {
        HallDTO d = new HallDTO();
        d.setCinemaId(1L);
        d.setName("1 号厅");
        d.setSeatRows(rows);
        d.setSeatCols(cols);
        return d;
    }

    /**
     * 让事务模板 mock **真的执行**事务体。
     *
     * <p>{@code TransactionTemplate} 是 mock, 默认 {@code executeWithoutResult} 什么都不做 ——
     * 于是 {@code hallMapper.updateById} / {@code seatMapper.delete} 一次都不会被调,
     * 断言「允许改」就变成了「什么都没发生也算允许」。这里把回调放出来, 让断言落在真实副作用上。
     */
    @BeforeEach
    void runTransactionBody() {
        doAnswer(inv -> {
            inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("改行列 + 该影厅有过订单 → 拒绝, 座位与影厅都不动")
    void update_layoutChangeWithOrders_rejected() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(8, 12));
        Session s = new Session();
        s.setId(99L);
        s.setHallId(HALL_ID);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));
        when(orderMapper.selectCount(any())).thenReturn(3L);

        assertThatThrownBy(() -> controller.update(HALL_ID, dto(10, 12)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能修改行列数");

        // 关键: 不能走到删座位那一步
        verify(seatMapper, never()).delete(any());
        verify(hallMapper, never()).updateById(any(Hall.class));
    }

    @Test
    @DisplayName("改行列 + 该影厅没有任何订单 → 允许, 并清掉布局缓存与场次缓存")
    void update_layoutChangeWithoutOrders_allowed() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(8, 12));
        // 该影厅有场次, 但场次下没有任何订单 —— 这是最常见的「可以改布局」情形
        Session s = new Session();
        s.setId(99L);
        s.setHallId(HALL_ID);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));
        when(orderMapper.selectCount(any())).thenReturn(0L);

        assertThatNoException().isThrownBy(() -> controller.update(HALL_ID, dto(10, 12)));

        verify(hallMapper).updateById(any(Hall.class));
        verify(hallLayoutCache).invalidate(HALL_ID);
        verify(sessionInfoCacheService).invalidate(99L);
    }

    @Test
    @DisplayName("只改名字(行列不变) + 该影厅有过订单 → 允许, 不该被行列防护误伤")
    void update_nameOnlyWithOrders_allowed() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(8, 12));
        Session s = new Session();
        s.setId(99L);
        s.setHallId(HALL_ID);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));
        when(orderMapper.selectCount(any())).thenReturn(3L);

        assertThatNoException().isThrownBy(() -> controller.update(HALL_ID, dto(8, 12)));

        verify(hallMapper).updateById(any(Hall.class));
    }

    @Test
    @DisplayName("行列比较必须是值比较: 行数 140(> Integer 缓存上限 127)不变时不算「改了」")
    void update_layoutCompareUsesValueNotReference() {
        // Integer.valueOf(140) 每次装箱都是不同对象 —— 用 != 比较会恒判「变了」,
        // 于是「没改行列」也会被行列防护拦下。
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(140, 140));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectCount(any())).thenReturn(5L);

        assertThatNoException().isThrownBy(() -> controller.update(HALL_ID, dto(140, 140)));

        verify(hallMapper).updateById(any(Hall.class));
    }

    @Test
    @DisplayName("改影厅后: 该厅名下每个场次的元数据缓存都要清")
    void update_invalidatesAllSessionCaches() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(8, 12));
        Session s1 = new Session();
        s1.setId(101L);
        s1.setHallId(HALL_ID);
        Session s2 = new Session();
        s2.setId(102L);
        s2.setHallId(HALL_ID);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s1, s2));
        when(orderMapper.selectCount(any())).thenReturn(0L);

        controller.update(HALL_ID, dto(8, 12));

        verify(sessionInfoCacheService).invalidate(101L);
        verify(sessionInfoCacheService).invalidate(102L);
    }

    @Test
    @DisplayName("删除影厅: 同样要清该厅名下场次的元数据缓存")
    void delete_invalidatesSessionCaches() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(8, 12));
        Session s = new Session();
        s.setId(101L);
        s.setHallId(HALL_ID);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));

        controller.delete(HALL_ID);

        verify(hallLayoutCache).invalidate(HALL_ID);
        verify(sessionInfoCacheService).invalidate(101L);
    }

    @Test
    @DisplayName("订单查询用 sessionId 批量查, 不按单查")
    void rejectLayoutChange_queriesOrdersOfHallSessions() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(8, 12));
        Session s = new Session();
        s.setId(99L);
        s.setHallId(HALL_ID);
        when(sessionMapper.selectList(any())).thenReturn(List.of(s));
        when(orderMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> controller.update(HALL_ID, dto(9, 12)))
                .isInstanceOf(BizException.class);

        // 订单确实被查过(而不是绕开订单直接放行)
        verify(orderMapper).selectCount(any());
    }
}

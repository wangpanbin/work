package com.cinema.modules.seat.service;

import com.cinema.modules.cinema.entity.Cinema;
import com.cinema.modules.cinema.mapper.CinemaMapper;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.seat.cache.SessionInfoCacheVO;
import com.cinema.modules.seat.reader.HallLayoutCache;
import com.cinema.modules.seat.reader.MyLockedSeatsReader;
import com.cinema.modules.seat.reader.SessionBitmapReader;
import com.cinema.modules.seat.reader.SessionInfoLoader;
import com.cinema.modules.seat.vo.SeatMapVO;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SeatService orchestrator 单测 — 锁定 seatMap() 4-reader 组合行为.
 * #4 拆分期间回归测试.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeatServiceTest {

    @Mock private SessionInfoLoader sessionInfoLoader;
    @Mock private SessionBitmapReader bitmapReader;
    @Mock private HallLayoutCache hallLayoutCache;
    @Mock private MyLockedSeatsReader myLockedSeatsReader;
    @Mock private OrderItemMapper orderItemMapper;

    private SeatService service;

    @BeforeEach
    void setUp() {
        service = new SeatService(sessionInfoLoader, bitmapReader, hallLayoutCache,
                myLockedSeatsReader, orderItemMapper);
    }

    @Test
    @DisplayName("seatMap: 缓存命中 → 不查 DB, 全部从 readers 拼装")
    void seatMap_assemblesFromReaders() {
        Long userId = 100L, sessionId = 1L;
        SessionInfoCacheVO info = new SessionInfoCacheVO();
        info.setSessionId(sessionId);
        info.setMovieTitle("Inception");
        info.setHallName("Hall-A");
        info.setStartTime(LocalDateTime.now().plusHours(2));
        info.setPrice(new BigDecimal("39.90"));
        info.setSeatRows(5);
        info.setSeatCols(8);
        info.setSeatCount(40);
        info.setHallId(10L);
        when(sessionInfoLoader.load(sessionId)).thenReturn(info);
        when(bitmapReader.readWithPadding(anyString(), anyInt())).thenReturn(new byte[5]);
        when(myLockedSeatsReader.load(userId, sessionId)).thenReturn(List.of(3, 5));
        when(hallLayoutCache.load(10L, 5, 8)).thenReturn(new com.cinema.modules.seat.vo.HallLayout(5, 8, List.of()));

        SeatMapVO vo = service.seatMap(sessionId, userId);

        assertThat(vo.getSessionId()).isEqualTo(sessionId);
        assertThat(vo.getMovieTitle()).isEqualTo("Inception");
        assertThat(vo.getHallName()).isEqualTo("Hall-A");
        assertThat(vo.getSeatCount()).isEqualTo(40);
        assertThat(vo.getMyLockedSeats()).containsExactly(3, 5);
    }

    @Test
    @DisplayName("seatMap: 4 reader 都查过, 不直连 mapper")
    void seatMap_noDirectMapperAccess() {
        Long userId = 100L, sessionId = 1L;
        SessionInfoCacheVO info = new SessionInfoCacheVO();
        info.setSessionId(sessionId);
        info.setHallId(10L);
        info.setSeatRows(5);
        info.setSeatCols(8);
        info.setSeatCount(40);
        when(sessionInfoLoader.load(sessionId)).thenReturn(info);
        when(bitmapReader.readWithPadding(anyString(), anyInt())).thenReturn(new byte[5]);
        when(myLockedSeatsReader.load(userId, sessionId)).thenReturn(List.of());
        when(hallLayoutCache.load(10L, 5, 8)).thenReturn(new com.cinema.modules.seat.vo.HallLayout(5, 8, List.of()));

        service.seatMap(sessionId, userId);

        verify(sessionInfoLoader).load(sessionId);
        verify(bitmapReader, org.mockito.Mockito.times(2)).readWithPadding(anyString(), eqInt(40));
        verify(myLockedSeatsReader).load(userId, sessionId);
        verify(hallLayoutCache).load(10L, 5, 8);
    }

    private static int eqInt(int v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }

    @Test
    @DisplayName("seatIndexesOf: 按 orderId 查 order_items, 返回 sorted 索引列表")
    void seatIndexesOf_returnsSortedIndexes() {
        Long orderId = 100L;
        OrderItem i1 = new OrderItem();
        i1.setSeatIndex(11);
        OrderItem i2 = new OrderItem();
        i2.setSeatIndex(5);
        OrderItem i3 = new OrderItem();
        i3.setSeatIndex(8);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(i1, i2, i3));

        List<Integer> out = service.seatIndexesOf(orderId);

        assertThat(out).containsExactly(5, 8, 11);
    }
}
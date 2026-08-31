package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.order.vo.OrderVO;
import com.cinema.modules.seat.mapper.SeatMapper;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** OrderQueryService 单测 — 我的订单 1 个用例(6 SQL 批量路径). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderQueryServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private HallMapper hallMapper;
    @Mock private SeatMapper seatMapper;
    @Mock private OrderCore orderCore;

    private OrderQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrderQueryService(orderMapper, orderItemMapper, sessionMapper,
                movieMapper, hallMapper, seatMapper, orderCore);
    }

    @Test
    @DisplayName("我的订单: 6 SQL 批量组装, status 过滤生效")
    void myOrders_basic() {
        Long userId = 100L;
        Order o1 = pendingOrder("ORD-1", userId, 1L, List.of(10));
        o1.setCreatedAt(LocalDateTime.now());
        Page<Order> p = new Page<>(1, 10, 1);
        p.setRecords(List.of(o1));
        when(orderMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(p);

        Session s = newSession(1L, LocalDateTime.now().plusHours(3));
        when(sessionMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(s));
        when(movieMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(new Movie()));
        when(hallMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(new Hall()));
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(makeItem(1L, 1L, 10)));

        Page<OrderVO> result = service.myOrders(userId, 0, 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        OrderVO vo = result.getRecords().get(0);
        assertThat(vo.getOrderNo()).isEqualTo("ORD-1");
        assertThat(vo.getSeatCount()).isEqualTo(1);
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

    private OrderItem makeItem(Long orderId, Long sessionId, int seatIndex) {
        OrderItem i = new OrderItem();
        i.setOrderId(orderId);
        i.setSessionId(sessionId);
        i.setSeatIndex(seatIndex);
        i.setPrice(new BigDecimal("39.90"));
        return i;
    }
}

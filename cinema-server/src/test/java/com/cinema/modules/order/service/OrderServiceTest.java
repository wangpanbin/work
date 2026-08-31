package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.infra.delay.DelayQueue;
import com.cinema.infra.redis.LuaLockResult;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.vo.LockResultVO;
import com.cinema.modules.order.vo.OrderVO;
import com.cinema.modules.payment.service.MockPaymentService;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
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

/**
 * OrderService 关键路径单测 — 锁座 / 支付 / 取消 / 超时关单.
 * <p>作为 E1(拆分重构)的安全网,任何拆分改动必须保证这些测试继续通过.
 * <p>关注:行为契约(对外可见的副作用),不关注内部细节.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private HallMapper hallMapper;
    @Mock private SeatMapper seatMapper;
    @Mock private SeatLuaService seatLuaService;
    @Mock private DelayQueue delayQueue;
    @Mock private SeatEventPublisher seatEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private MockPaymentService mockPaymentService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        // transactionTemplate.execute 不实际开启事务, 直接执行回调
        lenient().when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        // redisTemplate 链式 stub, 部分测试用到
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);

        orderService = new OrderService(
                orderMapper, orderItemMapper, sessionMapper, movieMapper, hallMapper,
                seatMapper, seatLuaService, delayQueue, seatEventPublisher,
                redisTemplate, transactionTemplate, mockPaymentService, new ObjectMapper());
    }

    // ====================== 锁座 ======================

    @Test
    @DisplayName("锁座成功: 4 步链路(幂等检查→Lua 锁→建单→延迟队列)全部触发, 返回 LockResultVO")
    void lockSeats_success() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10, 11));

        Session session = newSession(sessionId, LocalDateTime.now().plusHours(3));
        when(sessionMapper.selectById(sessionId)).thenReturn(session);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(seatLuaService.lockSeats(anyString(), anyString(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));

        // 模拟 transactionTemplate 在 createPendingOrder 里的两次事务调用:
        //  1) insert Order → 返回带 id 的 Order
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setOrderNo("ORD-1");
            return 1;
        });

        LockResultVO vo = orderService.lockSeats(userId, dto);

        assertThat(vo).isNotNull();
        assertThat(vo.getOrderNo()).isEqualTo("ORD-1");
        assertThat(vo.getSeatIndexes()).containsExactly(10, 11);
        // 延迟关单入队
        verify(delayQueue).offer(eq("ORD-1"), eq(15L * 60 * 1000L));
        // user:pending 写入
        verify(valueOps).set(anyString(), eq("ORD-1"), any());
        // 广播 LOCKED
        verify(seatEventPublisher).publishLocked(eq(sessionId), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("锁座冲突: Lua 返回冲突, 抛 BizException 带 conflict 列表, 不入队不广播")
    void lockSeats_conflict() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10, 11));

        when(sessionMapper.selectById(sessionId)).thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(seatLuaService.lockSeats(anyString(), anyString(), anyList()))
                .thenReturn(new LuaLockResult(false, List.of(10)));

        assertThatThrownBy(() -> orderService.lockSeats(userId, dto))
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

        when(sessionMapper.selectById(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().minusMinutes(10)));

        assertThatThrownBy(() -> orderService.lockSeats(userId, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已开场");

        verify(seatLuaService, never()).lockSeats(anyString(), anyString(), anyList());
    }

    // ====================== 支付 ======================

    @Test
    @DisplayName("支付成功: CAS 成功 → confirmSeats → 清 user:pending → 广播 SOLD")
    void pay_success() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10, 11));
        when(orderMapper.selectByOrderNo(orderNo)).thenReturn(order);
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(1);
        when(seatLuaService.confirmSeats(anyString(), anyString(), anyList())).thenReturn(List.of(10, 11));

        orderService.pay(orderNo, userId);

        verify(mockPaymentService).mockPayChannel(orderNo);
        verify(orderMapper).casMarkPaid(orderNo);
        verify(seatLuaService).confirmSeats(
                eq(RedisKeys.sessionLock(1L)), eq(RedisKeys.sessionSold(1L)), eq(List.of(10, 11)));
        verify(redisTemplate).delete(RedisKeys.userPending(userId, 1L));
        verify(seatEventPublisher).publishSold(eq(1L), eq(List.of(10, 11)));
    }

    @Test
    @DisplayName("支付失败: CAS 返回 0(已被关单/已支付), 抛 BizException 不 confirmSeats")
    void pay_casFailed() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10));
        when(orderMapper.selectByOrderNo(orderNo)).thenReturn(order);
        when(orderMapper.casMarkPaid(orderNo)).thenReturn(0);

        assertThatThrownBy(() -> orderService.pay(orderNo, userId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态已变化");

        verify(seatLuaService, never()).confirmSeats(anyString(), anyString(), anyList());
        verify(seatEventPublisher, never()).publishSold(anyLong(), anyList());
    }

    // ====================== 取消 / 关单 ======================

    @Test
    @DisplayName("主动取消: 状态 = PENDING_PAY 时, CAS → releaseSeats → 广播 RELEASED")
    void cancel_success() {
        Long userId = 100L;
        String orderNo = "ORD-1";
        Order order = pendingOrder(orderNo, userId, 1L, List.of(10, 11));
        when(orderMapper.selectByOrderNo(orderNo)).thenReturn(order);
        when(orderMapper.casCancel(orderNo)).thenReturn(1);
        when(seatLuaService.releaseSeats(anyString(), anyString(), anyList())).thenReturn(List.of(10, 11));

        orderService.cancel(orderNo, userId);

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

        boolean result = orderService.closeIfUnpaid("ORD-1");

        assertThat(result).isFalse();
        verify(orderMapper, never()).casCancel(anyString());
        verify(seatLuaService, never()).releaseSeats(anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("关单成功: 状态 PENDING_PAY, CAS 成功 → release → 广播(座位从 order_item 复用)")
    void closeIfUnpaid_success() {
        Order order = pendingOrder("ORD-1", 100L, 1L, List.of(10));
        when(orderMapper.selectByOrderNo("ORD-1")).thenReturn(order);
        when(orderMapper.casCancel("ORD-1")).thenReturn(1);
        // closeIfUnpaid 内部 setSeatIndexCache(seatIndexesOf(orderId)) → 走 order_item 查询
        OrderItem item = new OrderItem();
        item.setSeatIndex(10);
        when(orderItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item));
        when(seatLuaService.releaseSeats(anyString(), anyString(), anyList())).thenReturn(List.of(10));

        boolean result = orderService.closeIfUnpaid("ORD-1");

        assertThat(result).isTrue();
        verify(seatEventPublisher).publishReleased(eq(1L), eq(List.of(10)));
    }

    // ====================== 我的订单 ======================

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

        Page<OrderVO> result = orderService.myOrders(userId, 0, 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        OrderVO vo = result.getRecords().get(0);
        assertThat(vo.getOrderNo()).isEqualTo("ORD-1");
        assertThat(vo.getSeatCount()).isEqualTo(1);
    }

    // ====================== 工具 ======================

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

    /** PENDING_PAY 订单, paidAt 留空, seatIndexCache 预设好 */
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

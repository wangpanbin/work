package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.infra.delay.DelayQueue;
import com.cinema.infra.redis.LuaLockResult;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.redis.SeatWrite;
import com.cinema.infra.ws.AdminEventPublisher;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.CloseReason;
import com.cinema.modules.order.enums.OrderStatus;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OrderLockService 单测 — 锁座下单 3 个分支。 */
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
    @Mock private AdminEventPublisher adminEventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private OrderCore orderCore;

    private OrderLockService service;
    /** spy: 保留真实关单行为(CAS → 释放座位 → 发事件), 同时能断言「close 从未被调用」。 */
    private PendingOrderCloser pendingOrderCloser;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any(TransactionCallback.class)))
                .thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        // 真实的 query / closer, 让「顶掉旧单」这条链路也被验到
        PendingOrderQuery pendingOrderQuery = new PendingOrderQuery(orderMapper);
        pendingOrderCloser = spy(new PendingOrderCloser(orderMapper, seatLuaService,
                seatEventPublisher, adminEventPublisher, redisTemplate, orderCore));
        service = new OrderLockService(orderMapper, orderItemMapper, sessionMapper,
                seatLuaService, seatBitmapGuard, delayQueue, seatEventPublisher, adminEventPublisher,
                redisTemplate, transactionTemplate, orderCore, pendingOrderQuery, pendingOrderCloser);
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
        when(seatLuaService.lock(anyLong(), anyList()))
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
        when(seatLuaService.lock(anyLong(), anyList()))
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

        verify(seatLuaService, never()).lock(anyLong(), anyList());
    }

    /**
     * 死分支收尾 (候选 #5): 用户已有 PENDING_PAY 订单, lockSeats 应走 closePending 关闭旧单。
     * 同时断言 redisTemplate.hasKey 从未被调用——证明死分支已被清理。
     */
    @Test
    @DisplayName("已有 PENDING_PAY: lockSeats 关闭旧单, 不调用 redis.hasKey")
    void lockSeats_closesPreviousPending_whenUserAlreadyHasPendingOrder() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10));

        // 用户已有待支付单
        Order pending = new Order();
        pending.setId(999L);
        pending.setOrderNo("OLD-ORDER");
        pending.setUserId(userId);
        pending.setSessionId(sessionId);
        pending.setStatus(OrderStatus.PENDING_PAY.getCode());

        when(orderCore.requirePurchasableSession(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pending);
        // closePending 链路
        when(orderMapper.casCancel("OLD-ORDER")).thenReturn(1);
        when(orderCore.seatIndexesOf(pending)).thenReturn(List.of(5, 6));
        // 新锁链路
        when(seatLuaService.lock(anyLong(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setOrderNo("ORD-NEW");
            return 1;
        });

        service.lockSeats(userId, dto);

        // closePending 真的跑了
        verify(orderMapper).casCancel("OLD-ORDER");
        verify(redisTemplate).delete(RedisKeys.userPending(userId, sessionId));
        // REPLACED 关单: 旧单座位被释放(RELEASED 事件照发)
        verify(seatEventPublisher).publishReleased(eq(sessionId), eq(List.of(5, 6)));
        // 但不发管理端大屏事件 —— 顶替不是一次取消, 计入流水会重复计数
        verify(adminEventPublisher, never()).publish(eq("REPLACED"), any());
        // 死分支清理: hasKey 必须从未被调用
        verify(redisTemplate, never()).hasKey(anyString());
    }

    /**
     * 死分支收尾 (候选 #5): 用户无 PENDING_PAY 订单时, lockSeats 应直接走新锁链路。
     * 同时断言 redisTemplate.hasKey 从未被调用——证明死分支已被清理。
     */
    @Test
    @DisplayName("无 PENDING_PAY: lockSeats 跳过 close 路径, 不调用 redis.hasKey")
    void lockSeats_skipsClosePath_whenNoPendingOrder() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10));

        when(orderCore.requirePurchasableSession(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(seatLuaService.lock(anyLong(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setOrderNo("ORD-NEW");
            return 1;
        });

        service.lockSeats(userId, dto);

        // closePending 没跑
        verify(orderMapper, never()).casCancel(anyString());
        // 死分支清理: hasKey 必须从未被调用
        verify(redisTemplate, never()).hasKey(anyString());
    }

    // =================================================================
    // P0-2 (2026-10-25): 同场次重复锁座的「关旧单 / 锁新座」先后顺序
    // =================================================================

    /**
     * 用例 1: 旧单存在 + 新旧座位**无交集** + 新锁**失败**。
     * 规格: 旧单必须原样保留 —— 用户不能两头落空(既没旧单也没新单)。
     */
    @Test
    @DisplayName("同场次重选(座位无交集)且新锁失败: 旧单原样保留, 不许两头落空")
    void lockSeats_keepsOldOrder_whenNewLockFailsAndSeatsDoNotOverlap() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10, 11));

        Order pending = pendingOrder(userId, sessionId, "OLD-ORDER");
        when(orderCore.requirePurchasableSession(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pending);
        // 旧单座位 {5,6}, 新选 {10,11} → 无交集
        when(orderCore.seatIndexesOf(pending)).thenReturn(List.of(5, 6));
        when(seatLuaService.lock(anyLong(), anyList()))
                .thenReturn(new LuaLockResult(false, List.of(10)));

        assertThatThrownBy(() -> service.lockSeats(userId, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被占用");

        // 旧单一个字都没动: 关单从未发生
        verify(pendingOrderCloser, never()).close(any(), any());
        verify(orderMapper, never()).casCancel(anyString());
        // 也没有新订单
        verify(orderMapper, never()).insert(any(Order.class));
        // 旧座位没被释放 —— 用户的旧单还占着它
        verify(seatLuaService, never()).releaseUnpaid(any());
    }

    /**
     * 用例 2: 旧单存在 + 新旧座位**无交集** + 新锁**成功**。
     * 规格: 先锁新座 → 再关旧单 → 写新订单(uk_user_pending 要求关旧单先于写新单)。
     */
    @Test
    @DisplayName("同场次重选(座位无交集)且新锁成功: 先锁新座后关旧单, 新订单建成")
    void lockSeats_closesOldOrder_afterNewLock_whenSeatsDoNotOverlap() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10, 11));

        Order pending = pendingOrder(userId, sessionId, "OLD-ORDER");
        when(orderCore.requirePurchasableSession(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pending);
        when(orderCore.seatIndexesOf(pending)).thenReturn(List.of(5, 6));
        when(seatLuaService.lock(anyLong(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));
        when(orderMapper.casCancel("OLD-ORDER")).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setOrderNo("ORD-NEW");
            return 1;
        });

        LockResultVO vo = service.lockSeats(userId, dto);

        assertThat(vo.getOrderNo()).isEqualTo("ORD-NEW");
        // 旧单被顶掉
        verify(pendingOrderCloser).close(eq(pending), eq(CloseReason.REPLACED));
        verify(orderMapper).casCancel("OLD-ORDER");
        // 新订单建成
        verify(orderMapper).insert(any(Order.class));
        // 顺序: 锁新座 → 关旧单
        InOrder inOrder = inOrder(seatLuaService, pendingOrderCloser);
        inOrder.verify(seatLuaService).lock(sessionId, List.of(10, 11));
        inOrder.verify(pendingOrderCloser).close(eq(pending), eq(CloseReason.REPLACED));
    }

    /**
     * 用例 2b: 旧单存在 + 新锁成功 + **关旧单抛异常**。
     *
     * <p>规格: 新座位已经锁上了, 但旧单没关成 → 这时新座位会变成**幽灵锁**
     * (位图占着, 却没有订单持有它)。必须补偿释放, 否则那排座位谁都买不了。
     * 补偿走 {@code releaseUnpaid}(只清 lock 位, 不动 sold 位)。
     */
    @Test
    @DisplayName("关旧单抛异常: 补偿释放新座位, 不留幽灵锁")
    void lockSeats_releasesNewSeats_whenClosingOldOrderFails() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10, 11));

        Order pending = pendingOrder(userId, sessionId, "OLD-ORDER");
        when(orderCore.requirePurchasableSession(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pending);
        when(orderCore.seatIndexesOf(pending)).thenReturn(List.of(5, 6));
        when(seatLuaService.lock(anyLong(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));
        // 关旧单失败(CAS 已提交但座位没释放 / DB 报错)
        when(pendingOrderCloser.close(eq(pending), eq(CloseReason.REPLACED)))
                .thenThrow(new BizException("座位释放失败"));

        assertThatThrownBy(() -> service.lockSeats(userId, dto))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("座位释放失败");

        // 新座位必须被补偿释放 —— 否则它们变成「位图占着但没有订单」的幽灵锁
        ArgumentCaptor<SeatWrite.Unpaid> captor = ArgumentCaptor.forClass(SeatWrite.Unpaid.class);
        verify(seatLuaService).releaseUnpaid(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
        assertThat(captor.getValue().seats()).containsExactly(10, 11);
        // 补偿后不能再建新订单 —— 座位已经还回去了
        verify(orderMapper, never()).insert(any(Order.class));
    }

    /**
     * 用例 3: 旧单存在 + 新旧座位**有交集**(用户重选了自己已锁的座位)。
     * 规格: 旧单的锁还在位图上, 不先释放就必然与自己冲突 → 保持「先关旧单, 再锁新座」。
     */
    @Test
    @DisplayName("同场次重选(座位有交集): 必须先关旧单释放座位, 再锁新座")
    void lockSeats_closesOldOrder_beforeLock_whenSeatsOverlap() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(5, 11));

        Order pending = pendingOrder(userId, sessionId, "OLD-ORDER");
        when(orderCore.requirePurchasableSession(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(pending);
        // 旧单座位 {5,6}, 新选 {5,11} → 有交集(5)
        when(orderCore.seatIndexesOf(pending)).thenReturn(List.of(5, 6));
        when(seatLuaService.lock(anyLong(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));
        when(orderMapper.casCancel("OLD-ORDER")).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setOrderNo("ORD-NEW");
            return 1;
        });

        service.lockSeats(userId, dto);

        InOrder inOrder = inOrder(pendingOrderCloser, seatLuaService);
        inOrder.verify(pendingOrderCloser).close(eq(pending), eq(CloseReason.REPLACED));
        inOrder.verify(seatLuaService).lock(sessionId, List.of(5, 11));
    }

    /**
     * 用例 4: 没有旧单 → 行为不变, 不走关单路径。
     */
    @Test
    @DisplayName("无旧单: 不关任何单, 直接锁新座建新单")
    void lockSeats_doesNotCloseOldOrder_whenNoPendingOrder() {
        Long userId = 100L, sessionId = 1L;
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(sessionId);
        dto.setSeatIndexes(List.of(10));

        when(orderCore.requirePurchasableSession(sessionId))
                .thenReturn(newSession(sessionId, LocalDateTime.now().plusHours(3)));
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(seatLuaService.lock(anyLong(), anyList()))
                .thenReturn(new LuaLockResult(true, List.of()));
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(123L);
            o.setOrderNo("ORD-NEW");
            return 1;
        });

        LockResultVO vo = service.lockSeats(userId, dto);

        assertThat(vo.getOrderNo()).isEqualTo("ORD-NEW");
        verify(pendingOrderCloser, never()).close(any(), any());
        verify(orderMapper, never()).casCancel(anyString());
    }

    private Order pendingOrder(Long userId, Long sessionId, String orderNo) {
        Order pending = new Order();
        pending.setId(999L);
        pending.setOrderNo(orderNo);
        pending.setUserId(userId);
        pending.setSessionId(sessionId);
        pending.setStatus(OrderStatus.PENDING_PAY.getCode());
        return pending;
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

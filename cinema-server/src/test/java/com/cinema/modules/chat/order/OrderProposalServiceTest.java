package com.cinema.modules.chat.order;

import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.service.OrderLockService;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.seat.service.SeatService;
import com.cinema.modules.seat.vo.SeatMapVO;
import com.cinema.modules.session.entity.Session;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderProposalService 单元测试 (ADR-0004).
 *
 * <p>锁住四条关键契约:
 * <ol>
 *   <li><b>价格/座位以服务端为准</b>:模型的报价被丢弃, 模型的座位要过三重校验</li>
 *   <li><b>模型座位失效时服务端重找</b>(决策 D8)</li>
 *   <li><b>提案座位绝不越界</b>(越界会撑爆 Redis 位图)</li>
 *   <li><b>存量待支付单要写进提案</b>(决策 D7, ADR-0002 证据 1)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderProposalServiceTest {

    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 1001L;
    private static final int SEAT_COUNT = 96;   // 8 行 x 12 列
    private static final int COLS = 12;
    private static final int ROWS = 8;
    private static final BigDecimal PRICE = new BigDecimal("45.00");
    private static final long TTL_SECONDS = 90L;

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private OrderCore orderCore;
    @Mock private SeatService seatService;
    @Mock private SeatMapper seatMapper;
    @Mock private OrderLockService orderLockService;

    private OrderProposalService service;

    @BeforeEach
    void setUp() {
        service = new OrderProposalService(redisTemplate, new ObjectMapper(), orderCore, seatService,
                seatMapper, orderLockService, TTL_SECONDS);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 注意: ValueOperations.set(K,V,Duration) 返回 void, 不能用 when(...).thenReturn(...) 桩它

        Session session = new Session();
        session.setId(SESSION_ID);
        session.setHallId(1L);
        session.setPrice(PRICE);
        session.setStatus(1);
        session.setStartTime(LocalDateTime.now().plusDays(1));
        when(orderCore.requirePurchasableSession(SESSION_ID)).thenReturn(session);

        when(seatMapper.selectList(any())).thenReturn(List.of(
                seat(40, 4, 5), seat(41, 4, 6), seat(52, 5, 5), seat(53, 5, 6)));
        when(orderLockService.findUserPendingOrder(USER_ID, SESSION_ID)).thenReturn(null);
    }

    private static Seat seat(int idx, int rowNo, int colNo) {
        Seat s = new Seat();
        s.setSeatIndex(idx);
        s.setRowNo(rowNo);
        s.setColNo(colNo);
        return s;
    }

    /** 构造一张座位图: lock/sold 用布尔数组描述(索引 = seatIndex) */
    private SeatMapVO seatMap(boolean[] locked, boolean[] sold) {
        return SeatMapVO.builder()
                .sessionId(SESSION_ID)
                .movieTitle("流浪地球")
                .hallName("1号厅")
                .startTime(LocalDateTime.now().plusDays(1))
                .price(PRICE)
                .rows(ROWS)
                .cols(COLS)
                .seatCount(SEAT_COUNT)
                .lockBitmap(encode(locked))
                .soldBitmap(encode(sold))
                .build();
    }

    /** 把 boolean[] 编成 Base64 位图(字节内高位在前, 与 utils/bitmap.ts 前端解码一致) */
    private static String encode(boolean[] bits) {
        byte[] bytes = new byte[Math.max(1, (bits.length + 7) / 8)];
        for (int i = 0; i < bits.length; i++) {
            if (!bits[i]) {
                continue;
            }
            bytes[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private boolean[] none() {
        return new boolean[SEAT_COUNT];
    }

    // =================================================================
    // 正常路径
    // =================================================================

    @Test
    @DisplayName("模型给的座位有效连座 → 采纳之, 价格取 session 表, 总额 = 单价 x 张数")
    void modelSeatsValid_thenAdoptedWithServerPrice() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 2, List.of(52, 53));

        assertThat(p.getSeatIndexes()).containsExactly(52, 53);
        assertThat(p.getSeatDesc()).isEqualTo("5排5座、5排6座");
        // 模型的报价被丢弃, 一律取 session 表
        assertThat(p.getUnitPrice()).isEqualByComparingTo(PRICE);
        assertThat(p.getTotalAmount()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(p.getUserId()).isEqualTo(USER_ID);
        assertThat(p.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(p.getProposalId()).isNotBlank();
        assertThat(p.getExpiresAt()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    @DisplayName("提案写入 Redis: key = cinema:chat:proposal:{id}, TTL = 90s")
    void proposal_isPersistedWithConfiguredTtl() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 2, List.of(52, 53));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(keyCaptor.capture(), anyString(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo("cinema:chat:proposal:" + p.getProposalId());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(TTL_SECONDS));
    }

    // =================================================================
    // 决策 D8: 座位以服务端为准
    // =================================================================

    @Test
    @DisplayName("模型给的座位已被别人锁走 → 服务端重找一组连座(以服务端为准)")
    void modelSeatsTaken_thenServerPicksAnotherSet() {
        boolean[] locked = none();
        locked[52] = true;
        locked[53] = true;
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(locked, none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 2, List.of(52, 53));

        // 8x12 网格, 中段优先 → 第 4 行(1-based)的 5/6 座
        assertThat(p.getSeatIndexes()).containsExactly(40, 41);
        assertThat(p.getSeatIndexes()).doesNotContain(52, 53);
    }

    @Test
    @DisplayName("模型给的座位越界 → 重找, 且提案座位一定落在 [0, seatCount) 内")
    void modelSeatsOutOfRange_thenRepickedWithinRange() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 2, List.of(999_999));

        assertThat(p.getSeatIndexes()).isNotEmpty();
        assertThat(p.getSeatIndexes()).allSatisfy(idx -> assertThat(idx).isBetween(0, SEAT_COUNT - 1));
    }

    @Test
    @DisplayName("模型给的座位不连座 → 重找成连座")
    void modelSeatsNotContiguous_thenRepickedContiguous() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 2, List.of(52, 54));

        List<Integer> seats = p.getSeatIndexes();
        assertThat(seats).hasSize(2);
        assertThat(seats.get(1) - seats.get(0)).isEqualTo(1);
        assertThat(seats.get(0) / COLS).isEqualTo(seats.get(1) / COLS);
    }

    @Test
    @DisplayName("全场无连座 → 业务错误(不建提案)")
    void noContiguousSeats_thenBusinessError() {
        boolean[] allLocked = new boolean[SEAT_COUNT];
        java.util.Arrays.fill(allLocked, true);
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(allLocked, none()));

        assertThatThrownBy(() -> service.createProposal(USER_ID, SESSION_ID, 2, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("连座");
    }

    // =================================================================
    // 边界
    // =================================================================

    @Test
    @DisplayName("未登录 → 拒绝(40101), 不建提案")
    void anonymousUser_thenRejected() {
        assertThatThrownBy(() -> service.createProposal(null, SESSION_ID, 2, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ResultCode.UNAUTHORIZED.getCode()));
    }

    @Test
    @DisplayName("场次不可购(requirePurchasableSession 抛错)→ 透传该错误, 不建提案")
    void sessionNotPurchasable_thenPropagates() {
        when(orderCore.requirePurchasableSession(SESSION_ID))
                .thenThrow(new BizException("场次已开场,无法购票"));

        assertThatThrownBy(() -> service.createProposal(USER_ID, SESSION_ID, 2, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已开场");
    }

    @Test
    @DisplayName("count 越界(>4) → 钳制到 4 张")
    void countOutOfRange_thenClampedToFour() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 99, null);

        assertThat(p.getSeatIndexes()).hasSize(4);
    }

    @Test
    @DisplayName("count 为 null 且无座位提示 → 默认 1 张")
    void nullCountAndNoHint_thenOneSeat() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, null, null);

        assertThat(p.getSeatIndexes()).hasSize(1);
    }

    // =================================================================
    // 决策 D7: 存量待支付单必须写进提案
    // =================================================================

    @Test
    @DisplayName("同场次已有待支付单 → 订单号写进提案(用户点确认前能看到将被取消)")
    void existingPendingOrder_thenRecordedInProposal() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));
        Order pending = new Order();
        pending.setOrderNo("ORD-123");
        pending.setExpireAt(LocalDateTime.now().plusMinutes(8));
        when(orderLockService.findUserPendingOrder(USER_ID, SESSION_ID)).thenReturn(pending);

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 2, List.of(52, 53));

        assertThat(p.getPendingCancelOrderNo()).isEqualTo("ORD-123");
        assertThat(p.getPendingCancelExpireAt()).isNotNull();
    }

    @Test
    @DisplayName("没有存量待支付单 → 提案里对应字段为 null(前端不显示警告行)")
    void noPendingOrder_thenNullFields() {
        when(seatService.seatMap(SESSION_ID, USER_ID)).thenReturn(seatMap(none(), none()));

        OrderProposal p = service.createProposal(USER_ID, SESSION_ID, 2, List.of(52, 53));

        assertThat(p.getPendingCancelOrderNo()).isNull();
        assertThat(p.getPendingCancelExpireAt()).isNull();
    }

    // =================================================================
    // 消费提案
    // =================================================================

    @Test
    @DisplayName("消费提案 → 读出即删(原子 GETDEL), 返回快照")
    void consumeProposal_thenReadsAndDeletes() throws Exception {
        OrderProposal saved = OrderProposal.builder()
                .proposalId("P-1").userId(USER_ID).sessionId(SESSION_ID)
                .seatIndexes(List.of(52, 53)).totalAmount(new BigDecimal("90.00"))
                .build();
        when(valueOps.getAndDelete("cinema:chat:proposal:P-1"))
                .thenReturn(new ObjectMapper().writeValueAsString(saved));

        OrderProposal got = service.consumeProposal("P-1", USER_ID);

        assertThat(got.getProposalId()).isEqualTo("P-1");
        assertThat(got.getSeatIndexes()).containsExactly(52, 53);
        verify(valueOps).getAndDelete("cinema:chat:proposal:P-1");
    }

    @Test
    @DisplayName("消费不存在的提案 → 业务错误「已过期」")
    void consumeMissingProposal_thenBusinessError() {
        when(valueOps.getAndDelete(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.consumeProposal("P-none", USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期");
    }

    @Test
    @DisplayName("消费他人提案 → 拒绝(40301), 防 IDOR")
    void consumeOthersProposal_thenForbidden() throws Exception {
        OrderProposal saved = OrderProposal.builder()
                .proposalId("P-2").userId(999L).sessionId(SESSION_ID)
                .seatIndexes(List.of(52, 53)).build();
        when(valueOps.getAndDelete(anyString()))
                .thenReturn(new ObjectMapper().writeValueAsString(saved));

        assertThatThrownBy(() -> service.consumeProposal("P-2", USER_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(ResultCode.FORBIDDEN.getCode()));
    }
}

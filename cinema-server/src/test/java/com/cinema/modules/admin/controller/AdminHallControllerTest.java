package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.annotation.TableField;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.hall.dto.HallDTO;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.seat.reader.HallLayoutCache;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminHallController} 单测 —— 2026-10-24 新增。
 *
 * <p>锁三条契约:
 * <ul>
 *   <li><b>改行列的防护</b>: 座位索引是 {@code seatIndex = (r-1)*cols + (c-1)},
 *       {@code cols} 一变同一个 {@code seat.id} 对应的 {@code seatIndex} 全变,
 *       已付费订单的 {@code order_item.seat_index} 与按场次的 Redis 位图(以 seatIndex 为下标)
 *       全部错位 → 已售座位变可选(超卖方向)+ 真实座位显示被占。所以有过订单就不许改行列。</li>
 *   <li><b>场次缓存失效</b>: 改影厅后必须清该厅名下场次的元数据缓存,
 *       否则 {@code GET /seat-map} 与 {@code SeatRangeGate} 会在 30 分钟内读到旧座位数。</li>
 *   <li><b>vipFromRow 必须读得回</b>(2026-10-25 新增): 管理端编辑影厅时会回填并原样提交
 *       VIP 起始行; 后端读不回这个值 → 前端只能提交 null → {@code update} 删光座位后
 *       按 null 重插 → 整厅 VIP 被静默清空(还不报错)。</li>
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

    // =====================================================================
    // vipFromRow 派生(读取侧)
    //
    // 管理端编辑影厅时会把 VIP 起始行回填并原样提交; 后端读不回这个值 →
    // 前端只能提交 null → update 删光座位后按 null 重插 → 整厅 VIP 被静默清空。
    // =====================================================================

    /** 模拟座位表 GROUP BY 聚合出来的一行。 */
    private static Map<String, Object> vipRow(Long hallId, Integer vipFromRow) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("hallId", hallId);
        row.put("vipFromRow", vipFromRow);
        return row;
    }

    @Test
    @DisplayName("列表: 影厅有 VIP 座位 → vipFromRow = VIP 起始行的行号(不是座位索引)")
    void list_withVipSeats_returnsVipFromRowAsRowNumber() {
        when(hallMapper.selectList(any())).thenReturn(List.of(hall(8, 12)));
        when(seatMapper.selectMaps(any())).thenReturn(List.of(vipRow(HALL_ID, 3)));

        R<List<Hall>> resp = controller.list(null);

        assertThat(resp.getData()).hasSize(1);
        Integer vipFromRow = resp.getData().get(0).getVipFromRow();
        assertThat(vipFromRow).isEqualTo(3);
        // 8x12 的厅里, 第 3 行的座位索引是 (3-1)*12 = 24 —— 语义必须是行号,
        // 前端回填后原样提交, 拿索引回去会把 VIP 区整个错位。
        assertThat(vipFromRow).isNotEqualTo(24);
    }

    @Test
    @DisplayName("列表: 影厅一个 VIP 座位都没有 → vipFromRow = null")
    void list_withoutVipSeats_returnsNullVipFromRow() {
        when(hallMapper.selectList(any())).thenReturn(List.of(hall(8, 12)));
        // 座位表里没有 seat_type=1 的行
        when(seatMapper.selectMaps(any())).thenReturn(List.of());

        R<List<Hall>> resp = controller.list(null);

        assertThat(resp.getData().get(0).getVipFromRow()).isNull();
    }

    @Test
    @DisplayName("列表: 多个影厅的 vipFromRow 互不串号, 且座位表只查一次(不许 N+1)")
    void list_multipleHalls_keepVipFromRowSeparatedAndQuerySeatOnce() {
        Hall h1 = hall(8, 12);
        h1.setId(1L);
        Hall h2 = hall(10, 14);
        h2.setId(2L);
        Hall h3 = hall(6, 10);
        h3.setId(3L);
        when(hallMapper.selectList(any())).thenReturn(List.of(h1, h2, h3));
        when(seatMapper.selectMaps(any())).thenReturn(List.of(vipRow(1L, 3), vipRow(2L, 8)));

        R<List<Hall>> resp = controller.list(null);

        List<Hall> halls = resp.getData();
        assertThat(halls.get(0).getVipFromRow()).isEqualTo(3);
        assertThat(halls.get(1).getVipFromRow()).isEqualTo(8);
        // 第三个厅没有 VIP 座位 → null, 不能被前两个厅的值串味
        assertThat(halls.get(2).getVipFromRow()).isNull();
        // 一次批量查询(GROUP BY hall_id)取回所有影厅的 VIP 起始行, 不在循环里逐个查
        verify(seatMapper, times(1)).selectMaps(any());
    }

    @Test
    @DisplayName("详情: 同样带上 vipFromRow")
    void detail_returnsVipFromRow() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(8, 12));
        when(seatMapper.selectMaps(any())).thenReturn(List.of(vipRow(HALL_ID, 5)));

        R<Hall> resp = controller.detail(HALL_ID);

        assertThat(resp.getData().getVipFromRow()).isEqualTo(5);
    }

    @Test
    @DisplayName("vipFromRow 是派生值: 必须 exist=false, 否则 MyBatis-Plus 会去读写不存在的列")
    void hall_vipFromRowIsDerivedNotPersisted() throws NoSuchFieldException {
        TableField tableField = Hall.class.getDeclaredField("vipFromRow").getAnnotation(TableField.class);

        assertThat(tableField).isNotNull();
        assertThat(tableField.exist()).isFalse();
    }

    @Test
    @DisplayName("回归: update 仍按 DTO 的 vipFromRow 重建座位(第 N 行起为 VIP)")
    void update_rebuildsSeatsByDtoVipFromRow() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(4, 3));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectCount(any())).thenReturn(0L);
        HallDTO d = dto(4, 3);
        d.setVipFromRow(3);

        controller.update(HALL_ID, d);

        ArgumentCaptor<Seat> captor = ArgumentCaptor.forClass(Seat.class);
        verify(seatMapper, times(12)).insert(captor.capture());
        List<Seat> seats = captor.getAllValues();
        // 第 1/2 行 → 普通座
        assertThat(seats).filteredOn((Seat s) -> s.getRowNo() < 3)
                .allMatch(s -> Integer.valueOf(0).equals(s.getSeatType()));
        // 第 3/4 行 → VIP
        assertThat(seats).filteredOn((Seat s) -> s.getRowNo() >= 3)
                .allMatch(s -> Integer.valueOf(1).equals(s.getSeatType()));
        assertThat(seats).filteredOn((Seat s) -> s.getRowNo() >= 3).hasSize(6);
    }

    @Test
    @DisplayName("⚠️ update 未带 vipFromRow(null) → 保留原有 VIP 区, 不静默清空")
    void update_withoutVipFromRow_keepsExistingVipZone() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(4, 3));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectCount(any())).thenReturn(0L);
        // 该厅现在第 3 行起是 VIP
        when(seatMapper.selectMaps(any())).thenReturn(List.of(vipRow(HALL_ID, 3)));
        HallDTO d = dto(4, 3);
        d.setVipFromRow(null);

        controller.update(HALL_ID, d);

        ArgumentCaptor<Seat> captor = ArgumentCaptor.forClass(Seat.class);
        verify(seatMapper, times(12)).insert(captor.capture());
        List<Seat> seats = captor.getAllValues();
        // 第 3/4 行仍是 VIP —— 请求没带 vipFromRow ≠ 「清空 VIP 区」
        assertThat(seats).filteredOn((Seat s) -> s.getRowNo() >= 3)
                .allMatch(s -> Integer.valueOf(1).equals(s.getSeatType()));
        assertThat(seats).filteredOn((Seat s) -> s.getRowNo() >= 3).hasSize(6);
    }

    @Test
    @DisplayName("update 带 vipFromRow → 以请求为准(能改 VIP 起始行)")
    void update_withVipFromRow_usesRequestedValue() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(4, 3));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(seatMapper.selectMaps(any())).thenReturn(List.of(vipRow(HALL_ID, 3)));
        HallDTO d = dto(4, 3);
        d.setVipFromRow(2);

        controller.update(HALL_ID, d);

        ArgumentCaptor<Seat> captor = ArgumentCaptor.forClass(Seat.class);
        verify(seatMapper, times(12)).insert(captor.capture());
        List<Seat> seats = captor.getAllValues();
        assertThat(seats).filteredOn((Seat s) -> s.getRowNo() >= 2)
                .allMatch(s -> Integer.valueOf(1).equals(s.getSeatType()));
        assertThat(seats).filteredOn((Seat s) -> s.getRowNo() >= 2).hasSize(9);
    }

    @Test
    @DisplayName("VIP 起始行超过行数 → 拒绝, 不许让整厅 VIP 静默消失")
    void update_rejectsVipFromRowBeyondSeatRows() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(6, 12));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectCount(any())).thenReturn(0L);
        // 该厅现在 VIP 从第 8 行起, 但管理员把行数改成了 6
        when(seatMapper.selectMaps(any())).thenReturn(List.of(vipRow(HALL_ID, 8)));
        HallDTO d = dto(6, 12);

        assertThatThrownBy(() -> controller.update(HALL_ID, d))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("VIP 起始行");

        // 必须在删座位之前就拦住 —— 座位删掉再重建, VIP 就没了
        verify(seatMapper, never()).delete(any());
        verify(seatMapper, never()).insert(any(Seat.class));
    }

    @Test
    @DisplayName("更新: vipFromRow 为 null 时沿用当前 VIP 区(不改 VIP 起始行)")
    void update_withoutVipFromRow_keepsCurrentVipStart() {
        when(hallMapper.selectById(HALL_ID)).thenReturn(hall(10, 14));
        when(sessionMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(seatMapper.selectMaps(any())).thenReturn(List.of(vipRow(HALL_ID, 8)));
        HallDTO d = dto(10, 14);
        d.setVipFromRow(null);

        controller.update(HALL_ID, d);

        ArgumentCaptor<Seat> captor = ArgumentCaptor.forClass(Seat.class);
        verify(seatMapper, times(140)).insert(captor.capture());
        // 第 8 行起仍是 VIP —— 「null = 保持原样」而不是「清空 VIP 区」
        assertThat(captor.getAllValues()).filteredOn((Seat s) -> s.getRowNo() >= 8)
                .allMatch(s -> Integer.valueOf(1).equals(s.getSeatType()));
    }
}

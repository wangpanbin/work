package com.cinema.modules.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminSessionController} 单测 —— 2026-10-24 新增。
 *
 * <p>锁两条契约:
 * <ul>
 *   <li><b>删除场次前校验存量订单</b>: 原先直接删, 已付费订单变孤儿 →
 *       营收导出与看板的 LEFT JOIN 全变 NULL, 而且**静默**。</li>
 *   <li><b>分页端点与旧契约并存</b>: {@code GET /api/admin/sessions} 保持裸
 *       {@code List<Session>} 不变(改了会破坏前端契约), 新增
 *       {@code GET /api/admin/sessions/page} 走 {@code PageParams} 钳制。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminSessionControllerTest {

    @Mock private SessionMapper sessionMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private SeatBitmapGuard seatBitmapGuard;
    @Mock private SessionInfoCacheService sessionInfoCacheService;
    @Mock private OrderMapper orderMapper;

    @InjectMocks
    private AdminSessionController controller;

    private static final Long SESSION_ID = 7L;

    private Session session() {
        Session s = new Session();
        s.setId(SESSION_ID);
        s.setMovieId(1L);
        s.setHallId(1L);
        return s;
    }

    private Order order(String orderNo, int status) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(orderNo);
        o.setSessionId(SESSION_ID);
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("删除场次 + 有未完结订单(已支付) → 拒绝, 场次不删, 报错里带订单号")
    void delete_withPaidOrder_rejected() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session());
        when(orderMapper.selectList(any())).thenReturn(List.of(order("ORD-A", OrderStatus.PAID.getCode())));

        assertThatThrownBy(() -> controller.delete(SESSION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未完结订单")
                .hasMessageContaining("ORD-A");

        verify(sessionMapper, never()).deleteById(eq(SESSION_ID));
    }

    @Test
    @DisplayName("删除场次 + 有待支付订单 → 同样拒绝")
    void delete_withPendingOrder_rejected() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session());
        when(orderMapper.selectList(any()))
                .thenReturn(List.of(order("ORD-P", OrderStatus.PENDING_PAY.getCode())));

        assertThatThrownBy(() -> controller.delete(SESSION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未完结订单");

        verify(sessionMapper, never()).deleteById(eq(SESSION_ID));
    }

    @Test
    @DisplayName("删除场次 + 只有已取消/已退款的历史订单 → 允许(否则历史营收会跟着场次消失)")
    void delete_withOnlyTerminalOrders_allowed() {
        when(sessionMapper.selectById(SESSION_ID)).thenReturn(session());
        // mock 代表「带状态过滤的查询结果」: 已取消 / 已退款不在 status IN (0,1,3) 里, 所以查不到
        when(orderMapper.selectList(any())).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> controller.delete(SESSION_ID));

        verify(sessionMapper).deleteById(SESSION_ID);
        verify(seatBitmapGuard).clear(SESSION_ID);
        verify(sessionInfoCacheService).invalidate(SESSION_ID);
    }

    @Test
    @DisplayName("分页端点: page<=0 / size<=0 被 PageParams 钳制(否则 MyBatis-Plus 静默关分页且 total=0)")
    void page_clampsParams() {
        when(sessionMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        controller.page(-1, -5, null);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(sessionMapper).selectPage(captor.capture(), any());
        assertThat(captor.getValue().getCurrent()).isEqualTo(1);
        assertThat(captor.getValue().getSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("分页端点: size 上限 100, 超出被截断")
    void page_clampsSizeToMax() {
        when(sessionMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        controller.page(2, 9999, null);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Page> captor = ArgumentCaptor.forClass(Page.class);
        verify(sessionMapper).selectPage(captor.capture(), any());
        assertThat(captor.getValue().getCurrent()).isEqualTo(2);
        assertThat(captor.getValue().getSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("旧的全量端点仍在, 且返回裸 List(不改老契约)")
    void list_stillReturnsFullList() throws NoSuchMethodException {
        when(sessionMapper.selectList(any())).thenReturn(List.of(session()));

        assertThat(controller.list(null)).isNotNull();
        assertThat(controller.list(null).getData()).hasSize(1);

        Method m = AdminSessionController.class.getMethod("list", Long.class);
        assertThat(m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)).isNotNull();
    }

    @Test
    @DisplayName("分页端点映射到 /api/admin/sessions/page, 且与 /{id} 并存不冲突")
    void page_hasItsOwnMapping() throws NoSuchMethodException {
        Method m = AdminSessionController.class.getMethod("page", int.class, int.class, Long.class);
        assertThat(m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
                .containsExactly("/page");
    }
}

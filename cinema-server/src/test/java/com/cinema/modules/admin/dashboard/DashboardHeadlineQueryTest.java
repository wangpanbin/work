package com.cinema.modules.admin.dashboard;

import com.cinema.modules.order.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DashboardHeadlineQuery 单测 — 4 张卡片 + 1 个 derived(取消/退款共用方法).
 * 锁定: 0 调用今日外的 SQL;status 编码 (1/2/4) 不漂移.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardHeadlineQueryTest {

    @Mock private OrderMapper orderMapper;
    private DashboardHeadlineQuery query;

    @BeforeEach
    void setUp() {
        query = new DashboardHeadlineQuery(orderMapper);
    }

    @Test
    @DisplayName("todayRevenue: 透传 mapper BigDecimal")
    void todayRevenue_passesThrough() {
        when(orderMapper.sumRevenueToday()).thenReturn(new BigDecimal("1234.50"));
        assertThat(query.todayRevenue()).isEqualByComparingTo("1234.50");
    }

    @Test
    @DisplayName("todayOrders: null 参数 → 全量")
    void todayOrders_passesNullForAll() {
        when(orderMapper.countToday(null)).thenReturn(42);
        assertThat(query.todayOrders()).isEqualTo(42);
    }

    @Test
    @DisplayName("todayPaid: status=1 → mapper 接收 1")
    void todayPaid_passesStatusOne() {
        when(orderMapper.countToday(1)).thenReturn(20);
        assertThat(query.todayPaid()).isEqualTo(20);
    }

    @Test
    @DisplayName("todayPendingSeats: mapper 返 null → 0(今日没有待付单)")
    void todayPendingSeats_nullBecomesZero() {
        when(orderMapper.sumTodayPendingSeats()).thenReturn(null);
        assertThat(query.todayPendingSeats()).isEqualTo(0);
    }

    @Test
    @DisplayName("todayCancelled 与 todayRefunded: status 2 和 4 各传一次")
    void todayClosed_passesCorrectStatuses() {
        when(orderMapper.countTodayClosed(2)).thenReturn(5);
        when(orderMapper.countTodayClosed(4)).thenReturn(3);
        assertThat(query.todayCancelled()).isEqualTo(5);
        assertThat(query.todayRefunded()).isEqualTo(3);
    }
}
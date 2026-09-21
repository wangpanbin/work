package com.cinema.modules.admin.dashboard;

import com.cinema.modules.admin.dto.DashboardSummaryVO;
import com.cinema.modules.admin.service.AdminDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * AdminDashboardService orchestrator 单测 — 验证 summary() 把 headline + trend 两个 query
 * 的结果正确组装到 DashboardSummaryVO 各字段(原 0 测试覆盖).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDashboardServiceTest {

    @Mock private DashboardHeadlineQuery headlineQuery;
    @Mock private DashboardTrendQuery trendQuery;

    private AdminDashboardService service;

    @BeforeEach
    void setUp() {
        service = new AdminDashboardService(headlineQuery, trendQuery);
    }

    @Test
    @DisplayName("summary(): 把两个 query 的结果正确组装进 DashboardSummaryVO 所有 9 个字段")
    void summary_composesBothQueries() {
        // Headline
        when(headlineQuery.todayRevenue()).thenReturn(new BigDecimal("1234.50"));
        when(headlineQuery.todayOrders()).thenReturn(10);
        when(headlineQuery.todayPaid()).thenReturn(7);
        when(headlineQuery.todayPendingSeats()).thenReturn(6);
        when(headlineQuery.todayCancelled()).thenReturn(1);
        when(headlineQuery.todayRefunded()).thenReturn(2);
        // Trend
        Map<String, Object> trendPoint = new LinkedHashMap<>();
        trendPoint.put("date", "2026-09-21");
        trendPoint.put("amount", 100L);
        when(trendQuery.weeklyTrend()).thenReturn(List.of(trendPoint));
        DashboardSummaryVO.TopMovie m = new DashboardSummaryVO.TopMovie();
        m.setTitle("X");
        m.setRevenue(new BigDecimal("100"));
        m.setOrders(1);
        when(trendQuery.topMoviesWeek()).thenReturn(List.of(m));
        when(trendQuery.topSessionsByOccupancy()).thenReturn(List.of());

        DashboardSummaryVO vo = service.summary();

        assertThat(vo.getTodayRevenue()).isEqualByComparingTo("1234.50");
        assertThat(vo.getTodayOrders()).isEqualTo(10);
        assertThat(vo.getTodayPaid()).isEqualTo(7);
        assertThat(vo.getTodayPendingSeats()).isEqualTo(6);
        assertThat(vo.getTodayCancelled()).isEqualTo(1);
        assertThat(vo.getTodayRefunded()).isEqualTo(2);
        assertThat(vo.getWeeklyTrend()).hasSize(1);
        assertThat(vo.getTopMovies()).hasSize(1);
        assertThat(vo.getTopMovies().get(0).getTitle()).isEqualTo("X");
        assertThat(vo.getTopSessions()).isEmpty();
    }
}
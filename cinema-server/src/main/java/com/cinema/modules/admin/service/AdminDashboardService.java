package com.cinema.modules.admin.service;

import com.cinema.modules.admin.dashboard.DashboardHeadlineQuery;
import com.cinema.modules.admin.dashboard.DashboardTrendQuery;
import com.cinema.modules.admin.dto.DashboardSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Admin Dashboard 顶层 orchestrator.
 *
 * <p>#3 收尾: summary() 从 148 行的 god class 缩成 9 行纯组合.
 * 所有聚合 / padding / 4-join 逻辑下沉到两个 query 模块:
 * <ul>
 *   <li>{@link DashboardHeadlineQuery} — 今日 4 张卡片(营收/订单/已付/待付座位/已取消/已退款)</li>
 *   <li>{@link DashboardTrendQuery}    — 7 日趋势 + Top 影片 + Top 场次占用率</li>
 * </ul>
 *
 * <p>本类不再持有 mapper 依赖, 只组合两个 query 的结果. 新增指标 = 在对应 query 加方法 + 在 summary 里多写一行 setter.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final DashboardHeadlineQuery headlineQuery;
    private final DashboardTrendQuery trendQuery;

    public DashboardSummaryVO summary() {
        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setTodayRevenue(headlineQuery.todayRevenue());
        vo.setTodayOrders(headlineQuery.todayOrders());
        vo.setTodayPaid(headlineQuery.todayPaid());
        vo.setTodayPendingSeats(headlineQuery.todayPendingSeats());
        vo.setTodayCancelled(headlineQuery.todayCancelled());
        vo.setTodayRefunded(headlineQuery.todayRefunded());
        vo.setWeeklyTrend(trendQuery.weeklyTrend());
        vo.setTopMovies(trendQuery.topMoviesWeek());
        vo.setTopSessions(trendQuery.topSessionsByOccupancy());
        return vo;
    }
}
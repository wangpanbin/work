package com.cinema.modules.admin.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** O3 管理端经营看板 — 汇总数据 */
@Data
public class DashboardSummaryVO {

    /** 今日 4 个核心数字 + 7 日趋势 */
    private BigDecimal todayRevenue;
    private Integer todayOrders;
    private Integer todayPaid;
    private Integer todayPendingSeats;
    private Integer todayCancelled;
    private Integer todayRefunded;

    /** 7 日票房趋势: [{date: '2026-08-25', amount: 1234.5}, ...] */
    private List<Map<String, Object>> weeklyTrend;

    /** TOP 5 影片(本周) */
    private List<TopMovie> topMovies;

    /** 上座率 TOP 10 场次 */
    private List<TopSession> topSessions;

    @Data
    public static class TopMovie {
        private String title;
        private BigDecimal revenue;
        private Integer orders;
    }

    @Data
    public static class TopSession {
        private Long sessionId;
        private String movieTitle;
        private String hallName;
        private java.time.LocalDateTime startTime;
        private BigDecimal occupancyRate;
    }
}

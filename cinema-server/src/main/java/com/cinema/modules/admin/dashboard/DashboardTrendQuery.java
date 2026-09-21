package com.cinema.modules.admin.dashboard;

import com.cinema.modules.admin.dto.DashboardSummaryVO;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dashboard 趋势查询 — 周营收 padding + Top 影片 + Top 场次占用率.
 *
 * <p>#3 收尾: 抽离 AdminDashboardService.summary() 内部的 weeklyTrend / topMoviesWeek /
 * topSessionsByOccupancy. 后者做了 4-join + BigDecimal 占位率, 是 Long Method 的根因;
 * 拆出后 AdminDashboardService 缩成 9 行 orchestrator.
 *
 * <p>7 日 padding 保持原行为: 以 paid_at 当日为中心, 向前 pad 6 天;
 * 用 LinkedHashMap 保证 date 升序, 与 mapper SQL 的 ORDER BY date ASC 对齐.
 */
@Component
@RequiredArgsConstructor
public class DashboardTrendQuery {

    private final OrderMapper orderMapper;
    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;

    /**
     * 周营收 7 日趋势(missing days 填 0).
     * 返回 List&lt;Map&lt;String,Object&gt;&gt; 是 #3 Q6=B 决定: 保持 wire format 不变,
     * 避免前端 Dashboard.vue 联动改动.
     */
    public List<Map<String, Object>> weeklyTrend() {
        List<Map<String, Object>> raw = orderMapper.weeklyTrend();
        Map<String, Long> byDate = new HashMap<>();
        for (Map<String, Object> row : raw) {
            // B-01 修复: MySQL Connector/J 把 DATE(paid_at) 映射成 java.sql.Date 而非 String,
            // 之前 (String) 强转会抛 ClassCastException 让 /api/admin/dashboard/summary 直接 500.
            // 这里 toString() 兼容 String / java.sql.Date / java.util.Date, JDK 默认格式就是
            // "YYYY-MM-DD", 与下方 today.minusDays(i).toString() 完全一致, 不会破坏 padding key.
            Object dateObj = row.get("date");
            String date = dateObj == null ? null : dateObj.toString();
            Number amount = (Number) row.get("amount");
            byDate.put(date, amount == null ? 0L : amount.longValue());
        }
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> padded = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            String dateStr = today.minusDays(i).toString();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", dateStr);
            row.put("amount", byDate.getOrDefault(dateStr, 0L));
            padded.add(row);
        }
        return padded;
    }

    /** Top 影片(本周 PAID 订单按营收排序, mapper 直接返 typed list) */
    public List<DashboardSummaryVO.TopMovie> topMoviesWeek() {
        return orderMapper.topMoviesWeek();
    }

    /**
     * Top 场次占用率.
     * 4-join 流水线 — 1 个 mapper 取 (sessionId, sold) +
     * 3 个 selectBatchIds 拿 session/movie/hall(避免 N+1).
     * 占位率 = sold / session.seatCount, HALF_UP 4 位精度.
     * 最终结果限 10 条.
     */
    public List<DashboardSummaryVO.TopSession> topSessionsByOccupancy() {
        List<SessionOccupancyRow> raw = orderMapper.sessionOccupancyWeek();
        if (raw.isEmpty()) return List.of();

        List<Long> sessionIds = raw.stream().map(SessionOccupancyRow::getSessionId).toList();
        Map<Long, Session> sessionMap = sessionMapper.selectBatchIds(sessionIds).stream()
                .collect(Collectors.toMap(Session::getId, s -> s));
        List<Long> movieIds = sessionMap.values().stream().map(Session::getMovieId).distinct().toList();
        Map<Long, Movie> movieMap = movieMapper.selectBatchIds(movieIds).stream()
                .collect(Collectors.toMap(Movie::getId, m -> m));
        List<Long> hallIds = sessionMap.values().stream().map(Session::getHallId).distinct().toList();
        Map<Long, Hall> hallMap = hallMapper.selectBatchIds(hallIds).stream()
                .collect(Collectors.toMap(Hall::getId, h -> h));

        List<DashboardSummaryVO.TopSession> out = new ArrayList<>(raw.size());
        for (SessionOccupancyRow row : raw) {
            Session session = sessionMap.get(row.getSessionId());
            if (session == null) continue;
            Hall hall = hallMap.get(session.getHallId());
            Movie movie = movieMap.get(session.getMovieId());
            int sold = row.getSold() == null ? 0 : row.getSold();
            int total = hall == null || hall.getSeatCount() == null ? 0 : hall.getSeatCount();
            BigDecimal rate = total == 0
                    ? BigDecimal.ZERO
                    : new BigDecimal(sold).divide(new BigDecimal(total), 4, RoundingMode.HALF_UP);
            DashboardSummaryVO.TopSession t = new DashboardSummaryVO.TopSession();
            t.setSessionId(session.getId());
            t.setMovieTitle(movie == null ? "" : movie.getTitle());
            t.setHallName(hall == null ? "" : hall.getName());
            t.setStartTime(session.getStartTime());
            t.setOccupancyRate(rate);
            out.add(t);
        }
        return out.size() > 10 ? out.subList(0, 10) : out;
    }
}
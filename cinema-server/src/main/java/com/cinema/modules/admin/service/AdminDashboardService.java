package com.cinema.modules.admin.service;

import com.cinema.infra.redis.SeatLuaService;
import com.cinema.modules.admin.dto.DashboardSummaryVO;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * O3 管理端经营看板 — 聚合 SQL 服务
 * <p>7 个指标 + 3 个 TOP, 单 endpoint 返回整体数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final OrderMapper orderMapper;
    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;
    private final SeatLuaService seatLuaService;

    public DashboardSummaryVO summary() {
        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setTodayRevenue(sumTodayRevenue());
        vo.setTodayOrders(countTodayByStatus(null));
        vo.setTodayPaid(countTodayByStatus(1));
        vo.setTodayPendingSeats(sumTodayPendingSeats());
        vo.setTodayCancelled(countTodayClosed(2));
        vo.setTodayRefunded(countTodayClosed(4));
        vo.setWeeklyTrend(weeklyTrend());
        vo.setTopMovies(topMoviesWeek());
        vo.setTopSessions(topSessionsByOccupancy());
        return vo;
    }

    private BigDecimal sumTodayRevenue() {
        return orderMapper.sumRevenueToday();
    }

    private Integer countTodayByStatus(Integer status) {
        return orderMapper.countToday(status);
    }

    private Integer sumTodayPendingSeats() {
        Integer n = orderMapper.sumTodayPendingSeats();
        return n == null ? 0 : n;
    }

    private Integer countTodayClosed(Integer status) {
        return orderMapper.countTodayClosed(status);
    }

    private List<Map<String, Object>> weeklyTrend() {
        List<Map<String, Object>> raw = orderMapper.weeklyTrend();
        // 补全缺失日期, 7 天连续
        Map<LocalDate, BigDecimal> byDate = new HashMap<>();
        for (Map<String, Object> row : raw) {
            Object d = row.get("date");
            Object a = row.get("amount");
            if (d == null) continue;
            byDate.put(LocalDate.parse(d.toString()), a == null ? BigDecimal.ZERO : new BigDecimal(a.toString()));
        }
        List<Map<String, Object>> filled = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Map<String, Object> point = new HashMap<>();
            point.put("date", d.toString());
            point.put("amount", byDate.getOrDefault(d, BigDecimal.ZERO));
            filled.add(point);
        }
        return filled;
    }

    private List<DashboardSummaryVO.TopMovie> topMoviesWeek() {
        List<Map<String, Object>> raw = orderMapper.topMoviesWeek();
        List<DashboardSummaryVO.TopMovie> out = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            DashboardSummaryVO.TopMovie t = new DashboardSummaryVO.TopMovie();
            t.setTitle((String) row.get("title"));
            t.setRevenue(row.get("revenue") == null ? BigDecimal.ZERO : new BigDecimal(row.get("revenue").toString()));
            t.setOrders(row.get("orders") == null ? 0 : ((Number) row.get("orders")).intValue());
            out.add(t);
        }
        return out;
    }

    private List<DashboardSummaryVO.TopSession> topSessionsByOccupancy() {
        // 1) 取本周已开场的 session 及其订单总和(已售座位)
        List<Map<String, Object>> raw = orderMapper.sessionOccupancyWeek();
        // 2) 拉取 sessionId 对应的 session/movie/hall
        if (raw.isEmpty()) return new ArrayList<>();
        List<Long> sessionIds = raw.stream()
                .map(r -> ((Number) r.get("sessionId")).longValue())
                .toList();
        Map<Long, Session> sessionMap = sessionMapper.selectBatchIds(sessionIds)
                .stream().collect(Collectors.toMap(Session::getId, s -> s));
        List<Long> movieIds = sessionMap.values().stream()
                .map(Session::getMovieId).distinct().toList();
        Map<Long, Movie> movieMap = movieMapper.selectBatchIds(movieIds)
                .stream().collect(Collectors.toMap(Movie::getId, m -> m));
        List<Long> hallIds = sessionMap.values().stream()
                .map(Session::getHallId).distinct().toList();
        Map<Long, Hall> hallMap = hallMapper.selectBatchIds(hallIds)
                .stream().collect(Collectors.toMap(Hall::getId, h -> h));

        // 3) 按 sold/座位数 计算上座率
        List<DashboardSummaryVO.TopSession> out = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Long sid = ((Number) row.get("sessionId")).longValue();
            Integer sold = row.get("sold") == null ? 0 : ((Number) row.get("sold")).intValue();
            Session s = sessionMap.get(sid);
            if (s == null) continue;
            Hall h = hallMap.get(s.getHallId());
            int total = h == null ? 0 : h.getSeatCount();
            BigDecimal rate = total == 0 ? BigDecimal.ZERO
                    : new BigDecimal(sold).divide(new BigDecimal(total), 4, java.math.RoundingMode.HALF_UP);
            DashboardSummaryVO.TopSession t = new DashboardSummaryVO.TopSession();
            t.setSessionId(sid);
            Movie m = movieMap.get(s.getMovieId());
            t.setMovieTitle(m == null ? "(已删除)" : m.getTitle());
            t.setHallName(h == null ? "" : h.getName());
            t.setStartTime(s.getStartTime());
            t.setOccupancyRate(rate);
            out.add(t);
        }
        return out.stream().limit(10).toList();
    }
}

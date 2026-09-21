package com.cinema.modules.admin.dashboard;

import com.cinema.modules.admin.dto.DashboardSummaryVO;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * DashboardTrendQuery 单测 — 周趋势 padding + topMovies + topSessions 4-join + 限 10.
 * 锁定: 7 日 padding 顺序 / 占用率 (sold/seatCount) 计算 / 限 10 条数.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardTrendQueryTest {

    @Mock private OrderMapper orderMapper;
    @Mock private SessionMapper sessionMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private HallMapper hallMapper;
    private DashboardTrendQuery query;

    @BeforeEach
    void setUp() {
        query = new DashboardTrendQuery(orderMapper, sessionMapper, movieMapper, hallMapper);
    }

    // ---------- weeklyTrend: 7-day padded ----------

    @Test
    @DisplayName("weeklyTrend: mapper 返 3 天数据 → service padding 出 7 天连续")
    void weeklyTrend_pads7Days() {
        // 用顺序 Map 模拟 mapper 返回顺序
        Map<String, Object> r1 = rowOf(LocalDate.now().minusDays(2), 100L);
        Map<String, Object> r2 = rowOf(LocalDate.now().minusDays(1), 200L);
        Map<String, Object> r3 = rowOf(LocalDate.now(), 300L);
        when(orderMapper.weeklyTrend()).thenReturn(List.of(r1, r2, r3));

        List<Map<String, Object>> out = query.weeklyTrend();

        assertThat(out).hasSize(7);
        assertThat(out.get(0).get("date")).isEqualTo(LocalDate.now().minusDays(6).toString());
        assertThat(out.get(6).get("date")).isEqualTo(LocalDate.now().toString());
        // 中间 3 天有数据
        long totalAmount = out.stream()
                .mapToLong(m -> ((Number) m.get("amount")).longValue())
                .sum();
        assertThat(totalAmount).isEqualTo(600L);
        // 缺数据的几天是 0
        long zeroCount = out.stream()
                .filter(m -> ((Number) m.get("amount")).longValue() == 0L)
                .count();
        assertThat(zeroCount).isEqualTo(4);
    }

    @Test
    @DisplayName("weeklyTrend: mapper 空 → 全 0 的 7 天")
    void weeklyTrend_emptyMapper_returns7Zeros() {
        when(orderMapper.weeklyTrend()).thenReturn(List.of());

        List<Map<String, Object>> out = query.weeklyTrend();

        assertThat(out).hasSize(7);
        out.forEach(m -> assertThat(((Number) m.get("amount")).longValue()).isEqualTo(0L));
    }

    /**
     * B-01 回归: MySQL Connector/J 把 DATE(paid_at) 映射成 java.sql.Date(不是 String).
     * 修复前 weeklyTrend() 第 52 行 (String) row.get("date") 直接抛 ClassCastException,
     * 整个 /api/admin/dashboard/summary 返回 50000. 修复后 service 端必须接受
     * java.sql.Date 类型, 把 toString() 当 ISO 日期 key 用(YYYY-MM-DD),
     * 与 wire format 契约一致.
     */
    @Test
    @DisplayName("B-01 回归: weeklyTrend 接受 mapper 返 java.sql.Date 类型,不抛 CCE 且正常 padding")
    void weeklyTrend_acceptsSqlDateColumnType() {
        // 模拟 MySQL Connector/J 对 DATE 类型的真实映射: java.sql.Date
        Map<String, Object> r1 = rowOfSqlDate(LocalDate.now().minusDays(1), 250L);
        Map<String, Object> r2 = rowOfSqlDate(LocalDate.now(), 175L);
        when(orderMapper.weeklyTrend()).thenReturn(List.of(r1, r2));

        List<Map<String, Object>> out = query.weeklyTrend();

        assertThat(out).hasSize(7);
        // java.sql.Date.toString() == "YYYY-MM-DD", padding key 直接匹配
        assertThat(out.get(6).get("date")).isEqualTo(LocalDate.now().toString());
        assertThat(((Number) out.get(6).get("amount")).longValue()).isEqualTo(175L);
        assertThat(((Number) out.get(5).get("amount")).longValue()).isEqualTo(250L);
        // 其余 5 天是 0
        long zeroCount = out.stream()
                .filter(m -> ((Number) m.get("amount")).longValue() == 0L)
                .count();
        assertThat(zeroCount).isEqualTo(5);
    }

    private static Map<String, Object> rowOfSqlDate(LocalDate date, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", java.sql.Date.valueOf(date));
        m.put("amount", amount);
        return m;
    }

    // ---------- topMoviesWeek ----------

    @Test
    @DisplayName("topMoviesWeek: 透传 typed list")
    void topMoviesWeek_passesThrough() {
        DashboardSummaryVO.TopMovie m1 = new DashboardSummaryVO.TopMovie();
        m1.setTitle("Inception");
        m1.setRevenue(new BigDecimal("500"));
        m1.setOrders(3);
        DashboardSummaryVO.TopMovie m2 = new DashboardSummaryVO.TopMovie();
        m2.setTitle("Tenet");
        m2.setRevenue(new BigDecimal("300"));
        m2.setOrders(2);
        when(orderMapper.topMoviesWeek()).thenReturn(List.of(m1, m2));

        List<DashboardSummaryVO.TopMovie> out = query.topMoviesWeek();

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getTitle()).isEqualTo("Inception");
    }

    // ---------- topSessionsByOccupancy ----------

    @Test
    @DisplayName("topSessions: 4-join 计算 occupancy = sold / hall.seatCount, 限 10")
    void topSessionsByOccupancy_joins4Tables() {
        SessionOccupancyRow row1 = new SessionOccupancyRow();
        row1.setSessionId(101L);
        row1.setSold(50);
        SessionOccupancyRow row2 = new SessionOccupancyRow();
        row2.setSessionId(102L);
        row2.setSold(80);
        when(orderMapper.sessionOccupancyWeek()).thenReturn(List.of(row1, row2));

        Session s1 = session(101L, 10L, 100);   // movieId=10, hallId=100
        Session s2 = session(102L, 20L, 200);   // movieId=20, hallId=200
        when(sessionMapper.selectBatchIds(anyList())).thenReturn(List.of(s1, s2));
        Movie mv10 = movie(10L, "Inception");
        Movie mv20 = movie(20L, "Tenet");
        when(movieMapper.selectBatchIds(anyList())).thenReturn(List.of(mv10, mv20));
        Hall h100 = hall(100L, "Hall-A", 100);  // seatCount=100
        Hall h200 = hall(200L, "Hall-B", 80);   // seatCount=80
        when(hallMapper.selectBatchIds(anyList())).thenReturn(List.of(h100, h200));

        List<DashboardSummaryVO.TopSession> out = query.topSessionsByOccupancy();

        assertThat(out).hasSize(2);
        // row1: 50/100 = 0.5000
        assertThat(out.get(0).getSessionId()).isEqualTo(101L);
        assertThat(out.get(0).getMovieTitle()).isEqualTo("Inception");
        assertThat(out.get(0).getHallName()).isEqualTo("Hall-A");
        assertThat(out.get(0).getOccupancyRate()).isEqualByComparingTo("0.5000");
        // row2: 80/80 = 1.0000
        assertThat(out.get(1).getOccupancyRate()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("topSessions: mapper 返 15 条 → 结果限 10 条")
    void topSessionsByOccupancy_capsAt10() {
        List<SessionOccupancyRow> rows = new java.util.ArrayList<>();
        for (long i = 1; i <= 15; i++) {
            SessionOccupancyRow r = new SessionOccupancyRow();
            r.setSessionId(i);
            r.setSold((int) i);
            rows.add(r);
        }
        when(orderMapper.sessionOccupancyWeek()).thenReturn(rows);
        when(sessionMapper.selectBatchIds(any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> session(id, 1L, 1L)).toList();
        });
        when(movieMapper.selectBatchIds(any())).thenReturn(List.of(movie(1L, "X")));
        when(hallMapper.selectBatchIds(any())).thenReturn(List.of(hall(1L, "H", 100)));

        List<DashboardSummaryVO.TopSession> out = query.topSessionsByOccupancy();

        assertThat(out).hasSize(10);
    }

    @Test
    @DisplayName("topSessions: mapper 空 → 空列表")
    void topSessionsByOccupancy_emptyMapper_returnsEmpty() {
        when(orderMapper.sessionOccupancyWeek()).thenReturn(List.of());

        List<DashboardSummaryVO.TopSession> out = query.topSessionsByOccupancy();

        assertThat(out).isEmpty();
    }

    // ---------- fixtures ----------

    private static Map<String, Object> rowOf(LocalDate date, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("amount", amount);
        return m;
    }

    private static Session session(long id, long movieId, long hallId) {
        Session s = new Session();
        s.setId(id);
        s.setMovieId(movieId);
        s.setHallId(hallId);
        s.setStartTime(LocalDateTime.now().plusHours(2));
        return s;
    }

    private static Movie movie(long id, String title) {
        Movie m = new Movie();
        m.setId(id);
        m.setTitle(title);
        return m;
    }

    private static Hall hall(long id, String name, int seatCount) {
        Hall h = new Hall();
        h.setId(id);
        h.setName(name);
        h.setSeatCount(seatCount);
        return h;
    }
}
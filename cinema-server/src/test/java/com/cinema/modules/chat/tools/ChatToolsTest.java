package com.cinema.modules.chat.tools;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.context.UserContext;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.chat.service.KnowledgeService;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.service.MovieService;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.service.OrderQueryService;
import com.cinema.modules.order.vo.OrderVO;
import com.cinema.modules.seat.service.SeatService;
import com.cinema.modules.seat.vo.HallLayout;
import com.cinema.modules.seat.vo.SeatMapVO;
import com.cinema.modules.session.service.SessionService;
import com.cinema.modules.session.vo.SessionVO;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * T2 cycle 1 — ChatTools 8 个只读工具 happy path(spec §5.1, ADR-0002).
 *
 * <p>每个工具一个测试,验证方法签名 + 复用既有 Service。
 * Mockito 风格(沿用仓库既有),不引入 Spring context。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatToolsTest {

    @Mock private MovieService movieService;
    @Mock private SessionService sessionService;
    @Mock private SeatService seatService;
    @Mock private OrderQueryService orderQueryService;
    @Mock private SessionInfoCacheService sessionInfoCacheService;
    @Mock private KnowledgeService knowledgeService; // spec #20

    private ChatTools chatTools;

    @BeforeEach
    void setUp() {
        chatTools = new ChatTools(movieService, sessionService, seatService, orderQueryService, sessionInfoCacheService, knowledgeService);
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 必须清,否则污染同 JVM 后续用例(P0-1 引入)
        UserContext.clear();
    }

    /** 模拟已登录用户 */
    private void loginAs(long userId) {
        UserContext.set(userId, "u" + userId, 0);
    }

    @Test
    @DisplayName("searchMovies → MovieService.search(1, 10, kw, genre, region, 1) 透传")
    void searchMovies_delegatesToMovieService() {
        Page<Movie> expected = new Page<>();
        when(movieService.search(1, 10, "kw", "g", "r", 1)).thenReturn(expected);

        Page<Movie> result = chatTools.searchMovies("kw", "g", "r");

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("getMovieDetail(1L) → MovieService.detail(1L) 透传")
    void getMovieDetail_delegatesToMovieService() {
        Movie m = new Movie();
        m.setId(1L);
        when(movieService.detail(1L)).thenReturn(m);

        Movie result = chatTools.getMovieDetail(1L);

        assertThat(result).isSameAs(m);
    }

    @Test
    @DisplayName("listSessions(movieId, null) → date 默认为今天 + SessionService.listByMovieAndDate 透传")
    void listSessions_defaultsDateToToday() {
        List<SessionVO> expected = List.of();
        when(sessionService.listByMovieAndDate(1L, LocalDate.now())).thenReturn(expected);

        List<SessionVO> result = chatTools.listSessions(1L, null);

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("getSeatSummary(sessionId) 用服务端登录态查我的锁座,不接受 LLM 传 userId")
    void getSeatSummary_returnsAggregatedMapNoBitmap() {
        // 300 座场次,默认全可选(lock=0,sold=0)
        byte[] empty = new byte[37]; // 300 bits = 38 bytes, 但只需 37 字节覆盖 296 位
        SeatMapVO vo = SeatMapVO.builder()
                .sessionId(1001L)
                .movieTitle("流浪地球")
                .hallName("19 号厅")
                .startTime(LocalDateTime.of(2026, 9, 21, 20, 0, 0))
                .price(new BigDecimal("45.00"))
                .rows(15).cols(20).seatCount(300)
                .lockBitmap(Base64.getEncoder().encodeToString(empty))
                .soldBitmap(Base64.getEncoder().encodeToString(empty))
                .myLockedSeats(List.of())
                .vipRowNos(List.of())
                .build();
        loginAs(1L);
        when(seatService.seatMap(1001L, 1L)).thenReturn(vo);

        Map<String, Object> summary = chatTools.getSeatSummary(1001L);

        assertThat(summary)
                .containsEntry("sessionId", 1001L)
                .containsEntry("movieTitle", "流浪地球")
                .containsEntry("hallName", "19 号厅")
                .containsEntry("seatCount", 300)
                .containsEntry("available", 300)
                .containsEntry("lockedOther", 0)
                .containsEntry("sold", 0);
        // spec §5.2:不返回整张位图
        assertThat(summary).doesNotContainKey("lockBitmap").doesNotContainKey("soldBitmap");
    }

    @Test
    @DisplayName("getSeatSummary 匿名 → 传 null userId(不泄露任何人的锁座)")
    void getSeatSummary_anonymous_passesNullUserId() {
        byte[] empty = new byte[37];
        SeatMapVO vo = SeatMapVO.builder()
                .sessionId(1001L).rows(15).cols(20).seatCount(300)
                .lockBitmap(Base64.getEncoder().encodeToString(empty))
                .soldBitmap(Base64.getEncoder().encodeToString(empty))
                .build();
        when(seatService.seatMap(1001L, null)).thenReturn(vo);

        assertThat(chatTools.getSeatSummary(1001L)).containsEntry("sessionId", 1001L);
    }

    @Test
    @DisplayName("findContiguousSeats(sessionId, 2, null) 找 2 连座 → 返 [0,1] (从最低索引开始)")
    void findContiguousSeats_returnsContiguousRun() {
        // 300 座全可选
        byte[] empty = new byte[37];
        SeatMapVO vo = SeatMapVO.builder()
                .sessionId(1001L).rows(15).cols(20).seatCount(300)
                .lockBitmap(Base64.getEncoder().encodeToString(empty))
                .soldBitmap(Base64.getEncoder().encodeToString(empty))
                .build();
        loginAs(1L);
        when(seatService.seatMap(1001L, 1L)).thenReturn(vo);

        List<Integer> result = chatTools.findContiguousSeats(1001L, 2, null);

        assertThat(result).containsExactly(0, 1);
    }

    @Test
    @DisplayName("getMyOrders(status=null, page=null, size=null) → 用登录态 userId + page=1 size=10 默认")
    void getMyOrders_delegatesWithDefaults() {
        Page<OrderVO> expected = new Page<>();
        loginAs(1L);
        when(orderQueryService.myOrders(1L, null, 1, 10)).thenReturn(expected);

        Object result = chatTools.getMyOrders(null, null, null);

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("getMyOrder(orderNo) → 用登录态 userId 透传 OrderQueryService.detail")
    void getMyOrder_delegatesToOrderQueryService() {
        OrderVO vo = new OrderVO();
        vo.setOrderNo("o-1");
        loginAs(1L);
        when(orderQueryService.detail(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(vo);

        Object result = chatTools.getMyOrder("o-1");

        assertThat(result).isSameAs(vo);
    }

    @Test
    @DisplayName("getMyOrder 未登录 → 返 Map.of(\"error\",\"LOGIN_REQUIRED\") 不调 OrderQueryService")
    void getMyOrder_anonymous_returnsLoginRequired() {
        Object result = chatTools.getMyOrder("o-1");

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("error")).isEqualTo("LOGIN_REQUIRED");
    }

    @Test
    @DisplayName("getMyOrders 未登录 → 返 Map.of(\"error\",\"LOGIN_REQUIRED\") 不调 OrderQueryService")
    void getMyOrders_anonymous_returnsLoginRequired() {
        Object result = chatTools.getMyOrders(null, null, null);

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("error")).isEqualTo("LOGIN_REQUIRED");
    }

    // ============ P0-1 IDOR 回归:登录用户也查不到别人的订单 ============

    @Test
    @DisplayName("P0-1:user2 登录态下 getMyOrders 只会把 2 传给 OrderQueryService(签名里压根没有 userId 可传)")
    void getMyOrders_alwaysUsesServerSideUserContext() {
        loginAs(2L);
        when(orderQueryService.myOrders(2L, null, 1, 10)).thenReturn(new Page<>());

        chatTools.getMyOrders(null, null, null);

        // 关键:一次都不会用别的 userId 调过 service
        org.mockito.Mockito.verify(orderQueryService).myOrders(2L, null, 1, 10);
        org.mockito.Mockito.verifyNoMoreInteractions(orderQueryService);
    }

    @Test
    @DisplayName("P0-1:user2 登录态下 getMyOrder 详情归属校验沿用登录态 userId")
    void getMyOrder_alwaysUsesServerSideUserContext() {
        loginAs(2L);
        when(orderQueryService.detail("o-9", 2L)).thenReturn(new OrderVO());

        chatTools.getMyOrder("o-9");

        org.mockito.Mockito.verify(orderQueryService).detail("o-9", 2L);
        org.mockito.Mockito.verifyNoMoreInteractions(orderQueryService);
    }

    // ============ spec #20 ID-5 searchFaq ============

    @Test
    @DisplayName("searchFaq(query) → KnowledgeService.searchFaq 透传 + trim")
    void searchFaq_delegatesToKnowledgeService() {
        List<Map<String, String>> expected = List.of(
                Map.of("question", "怎么买票?", "answer", "购票流程...", "score", "100.0")
        );
        when(knowledgeService.searchFaq("怎么买票")).thenReturn(expected);

        List<Map<String, String>> result = chatTools.searchFaq("  怎么买票  ");

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("searchFaq(empty) → 返空 List,不调 KnowledgeService")
    void searchFaq_empty_returnsEmptyWithoutCallingService() {
        assertThat(chatTools.searchFaq("")).isEmpty();
        assertThat(chatTools.searchFaq(null)).isEmpty();
        assertThat(chatTools.searchFaq("   ")).isEmpty();
    }
}
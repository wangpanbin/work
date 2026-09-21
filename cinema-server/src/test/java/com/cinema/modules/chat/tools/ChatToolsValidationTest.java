package com.cinema.modules.chat.tools;

import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.movie.service.MovieService;
import com.cinema.modules.order.service.OrderQueryService;
import com.cinema.modules.seat.service.SeatService;
import com.cinema.modules.session.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T2 cycle 2 — ChatTools 入参白名单校验(spec §5.5).
 *
 * <p>校验失败必抛 BizException,触发 §5.4(a) 路径(spec §5.5 第 3 条)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@code movieId} / {@code sessionId} 非正数</li>
 *   <li>{@code count} 越界(>4 或 <1)</li>
 *   <li>{@code orderNo} 空字符串</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatToolsValidationTest {

    @Mock private MovieService movieService;
    @Mock private SessionService sessionService;
    @Mock private SeatService seatService;
    @Mock private OrderQueryService orderQueryService;
    @Mock private SessionInfoCacheService sessionInfoCacheService;

    private ChatTools chatTools;

    @BeforeEach
    void setUp() {
        chatTools = new ChatTools(movieService, sessionService, seatService, orderQueryService, sessionInfoCacheService);
    }

    @Test
    @DisplayName("getMovieDetail(0L) → BizException movieId 必须为正数")
    void getMovieDetail_zeroId_throws() {
        assertThatThrownBy(() -> chatTools.getMovieDetail(0L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("movieId 必须为正数");
    }

    @Test
    @DisplayName("getMovieDetail(-1L) → BizException")
    void getMovieDetail_negativeId_throws() {
        assertThatThrownBy(() -> chatTools.getMovieDetail(-1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("movieId 必须为正数");
    }

    @Test
    @DisplayName("getMovieDetail(null) → BizException(不抛 NPE)")
    void getMovieDetail_nullId_throws() {
        assertThatThrownBy(() -> chatTools.getMovieDetail(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("movieId 必须为正数");
    }

    @Test
    @DisplayName("listSessions(0L, null) → BizException")
    void listSessions_zeroId_throws() {
        assertThatThrownBy(() -> chatTools.listSessions(0L, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("movieId 必须为正数");
    }

    @Test
    @DisplayName("getSeatSummary(0L, 1L) → BizException sessionId 必须为正数")
    void getSeatSummary_zeroSessionId_throws() {
        assertThatThrownBy(() -> chatTools.getSeatSummary(0L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("sessionId 必须为正数");
    }

    @Test
    @DisplayName("findContiguousSeats(1001L, 1L, 0, null) → BizException count 越界")
    void findContiguousSeats_countZero_throws() {
        assertThatThrownBy(() -> chatTools.findContiguousSeats(1001L, 1L, 0, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("count 必须在 [1,4]");
    }

    @Test
    @DisplayName("findContiguousSeats(1001L, 1L, 5, null) → BizException count 越界")
    void findContiguousSeats_countFive_throws() {
        assertThatThrownBy(() -> chatTools.findContiguousSeats(1001L, 1L, 5, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("count 必须在 [1,4]");
    }

    @Test
    @DisplayName("getMyOrder(\"\", 1L) → BizException orderNo 不能为空")
    void getMyOrder_emptyOrderNo_throws() {
        assertThatThrownBy(() -> chatTools.getMyOrder("", 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("orderNo 不能为空");
    }
}
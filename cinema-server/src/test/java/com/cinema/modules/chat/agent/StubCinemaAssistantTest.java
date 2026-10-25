package com.cinema.modules.chat.agent;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.modules.chat.service.ReplyCardParser;
import com.cinema.modules.chat.tools.ChatTools;
import com.cinema.modules.chat.vo.ActionCardVO;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.session.vo.SessionVO;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 假 LLM({@link StubCinemaAssistant})的行为测试。
 *
 * <p><b>重点不是「stub 返回了什么」, 而是「stub 的输出能不能被生产解析器吃进去」</b> ——
 * 所以断言直接走真的 {@link ReplyCardParser}, 而不是自己解一遍 JSON。
 * 契约变了(解析器改了字段名), 这里会红, 而不是让 E2E 悄悄假绿。
 */
class StubCinemaAssistantTest {

    private static final String TODAY = "今天是 2026-10-25(周日),明天是 2026-10-26,后天是 2026-10-27。";

    private ChatTools chatTools;
    private StubCinemaAssistant assistant;

    @BeforeEach
    void setUp() {
        chatTools = mock(ChatTools.class);
        assistant = new StubCinemaAssistant(chatTools);
    }

    private static Map<String, Object> summary(long sessionId, int available, int longestRun) {
        return summary(sessionId, available, longestRun,
                LocalDateTime.now().plusDays(1).withHour(19).withMinute(30).withSecond(0).withNano(0));
    }

    private static Map<String, Object> summary(long sessionId, int available, int longestRun,
                                               LocalDateTime startTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("movieTitle", "哪吒之魔童闹海");
        m.put("hallName", "1号激光厅");
        m.put("startTime", startTime);
        m.put("available", available);
        m.put("longestContiguousStart", 4);
        m.put("longestContiguousLength", longestRun);
        return m;
    }

    @Test
    @DisplayName("消息里给了场次 ID → 出 ORDER_PROPOSAL 卡, 且刻意不带 seatIndexes")
    void explicit_session_id_yields_proposal_without_seat_indexes() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(summary(1130202502L, 240, 8));

        Result<String> r = assistant.chat("s1", TODAY, "帮我买场次 1130202502 的两个连座");
        assertThat(r.content()).contains("```json-cards");

        ReplyCardParser.ParseResult parsed = ReplyCardParser.parse(r.content());
        assertThat(parsed.cards()).hasSize(1);
        ActionCardVO card = parsed.cards().get(0);
        assertThat(card.getType()).isEqualTo(ActionCardVO.TYPE_ORDER_PROPOSAL);
        assertThat(card.getSessionId()).isEqualTo("1130202502");
        assertThat(card.getCount()).isEqualTo(2);
        assertThat(card.getSeatIndexes())
                .as("stub 刻意不给座位 —— 服务端会重读实时位图自己挑连座(ADR-0004 决策 D8)")
                .isNull();
    }

    @Test
    @DisplayName("没说张数 → count 缺省 2; 明说「3 张」→ count=3")
    void count_defaults_to_two_and_honours_explicit_count() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(summary(1130202502L, 240, 8));

        ActionCardVO implied = onlyCard(assistant.chat("s1", TODAY, "帮我买场次 1130202502 的连座").content());
        assertThat(implied.getCount()).isEqualTo(2);

        ActionCardVO explicit = onlyCard(assistant.chat("s2", TODAY, "帮我买场次 1130202502 的 3 张票").content());
        assertThat(explicit.getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("余座不够 → 不出卡(宁可不说, 也不给一张必然失败的卡)")
    void insufficient_seats_yields_no_card() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(summary(1130202502L, 1, 1));

        String reply = assistant.chat("s1", TODAY, "帮我买场次 1130202502 的两个连座").content();
        assertThat(reply).doesNotContain("```json-cards");
        assertThat(ReplyCardParser.parse(reply).cards()).isEmpty();
    }

    @Test
    @DisplayName("消息里没场次 ID → 走只读工具自己挑一个有余座的场次")
    void falls_back_to_tools_to_pick_a_session() {
        Movie movie = mock(Movie.class);
        when(movie.getId()).thenReturn(7L);
        Page<Movie> page = new Page<>(1, 10);
        page.setRecords(List.of(movie));
        when(chatTools.searchMovies(null, null, null)).thenReturn(page);

        SessionVO s = new SessionVO();
        s.setId(2200302502L);
        when(chatTools.listSessions(eq(7L), any(LocalDate.class))).thenReturn(List.of(s));
        when(chatTools.getSeatSummary(2200302502L)).thenReturn(summary(2200302502L, 140, 6));

        ActionCardVO card = onlyCard(assistant.chat("s1", TODAY, "帮我推荐今晚的场次,要两个连座").content());
        assertThat(card.getType()).isEqualTo(ActionCardVO.TYPE_ORDER_PROPOSAL);
        assertThat(card.getSessionId()).isEqualTo("2200302502");
        assertThat(card.getCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("已开场的场次 → 不出卡(服务端会拒「场次已开场,无法购票」)")
    void already_started_session_yields_no_card() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(
                summary(1130202502L, 240, 8, LocalDateTime.now().minusHours(2)));

        String reply = assistant.chat("s1", TODAY, "帮我买场次 1130202502 的两个连座").content();
        assertThat(reply).doesNotContain("```json-cards");
    }

    @Test
    @DisplayName("token 用量必须有值 —— 看板靠它算成本, 记 null 会被当成「未知」")
    void token_usage_is_present() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(summary(1130202502L, 240, 8));
        Result<String> r = assistant.chat("s1", TODAY, "帮我买场次 1130202502 的两个连座");
        assertThat(r.tokenUsage()).isNotNull();
        assertThat(r.tokenUsage().inputTokenCount()).isNotNull();
        assertThat(r.tokenUsage().outputTokenCount()).isNotNull();
    }

    // ============ 座位建议分支(SEAT_SUGGESTION)============

    @Test
    @DisplayName("给了场次 ID 且在问座位 → 出 SEAT_SUGGESTION 卡, 且带 seatIndexes")
    void seat_question_with_explicit_session_id_yields_seat_suggestion() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(summary(1130202502L, 240, 8));
        when(chatTools.findContiguousSeats(1130202502L, 2, null)).thenReturn(List.of(24, 25));

        ActionCardVO card = onlyCard(
                assistant.chat("s1", TODAY, "场次 1130202502 的座位还有连座吗").content());

        assertThat(card.getType()).isEqualTo(ActionCardVO.TYPE_SEAT_SUGGESTION);
        assertThat(card.getSessionId()).isEqualTo("1130202502");
        assertThat(card.getSeatIndexes())
                .as("座位建议卡必须带座位索引 —— 前端靠它拼 preselect 预选")
                .containsExactly(24, 25);
        assertThat(card.getMovieTitle()).isEqualTo("哪吒之魔童闹海");
        assertThat(card.getHallName()).isEqualTo("1号激光厅");
    }

    @Test
    @DisplayName("问座位时明说张数 → 连座按那个张数找")
    void seat_question_honours_explicit_count() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(summary(1130202502L, 240, 8));
        when(chatTools.findContiguousSeats(1130202502L, 3, null)).thenReturn(List.of(30, 31, 32));

        ActionCardVO card = onlyCard(
                assistant.chat("s1", TODAY, "场次 1130202502 的 3 个座位给我挑一下").content());

        assertThat(card.getType()).isEqualTo(ActionCardVO.TYPE_SEAT_SUGGESTION);
        assertThat(card.getSeatIndexes()).containsExactly(30, 31, 32);
    }

    @Test
    @DisplayName("问座位但那个场次没有连座 → 不出卡(宁可回一句话, 也不给一张跳过去没座的卡)")
    void seat_question_without_contiguous_seats_yields_no_card() {
        when(chatTools.getSeatSummary(1130202502L)).thenReturn(summary(1130202502L, 240, 8));
        when(chatTools.findContiguousSeats(1130202502L, 2, null)).thenReturn(List.of());

        String reply = assistant.chat("s1", TODAY, "场次 1130202502 的座位还有连座吗").content();
        assertThat(reply).doesNotContain("```json-cards");
        assertThat(ReplyCardParser.parse(reply).cards()).isEmpty();
    }

    @Test
    @DisplayName("S22 那句「帮我推荐今晚的场次,要两个连座」→ 仍是 ORDER_PROPOSAL, 不被座位建议分支吸走")
    void s22_style_message_still_yields_order_proposal() {
        Movie movie = mock(Movie.class);
        when(movie.getId()).thenReturn(7L);
        Page<Movie> page = new Page<>(1, 10);
        page.setRecords(List.of(movie));
        when(chatTools.searchMovies(null, null, null)).thenReturn(page);

        SessionVO s = new SessionVO();
        s.setId(2200302502L);
        when(chatTools.listSessions(eq(7L), any(LocalDate.class))).thenReturn(List.of(s));
        when(chatTools.getSeatSummary(2200302502L)).thenReturn(summary(2200302502L, 140, 6));

        ActionCardVO card = onlyCard(
                assistant.chat("s1", TODAY, "帮我推荐今晚的场次,要两个连座").content());

        assertThat(card.getType())
                .as("S22 必须拿到 ORDER_PROPOSAL —— 点「确认下单」才建得出订单")
                .isEqualTo(ActionCardVO.TYPE_ORDER_PROPOSAL);
        // 「连座」不是「座位」: 座位建议分支的触发词不能把 S22 那句话吸走。
        verify(chatTools, never()).findContiguousSeats(any(), any(Integer.class), any());
    }

    @Test
    @DisplayName("只问座位但没给场次 ID → 不出座位建议卡(不能凭空挑一个场次)")
    void seat_question_without_explicit_session_id_yields_no_seat_card() {
        Movie movie = mock(Movie.class);
        when(movie.getId()).thenReturn(7L);
        Page<Movie> page = new Page<>(1, 10);
        page.setRecords(List.of(movie));
        when(chatTools.searchMovies(null, null, null)).thenReturn(page);

        SessionVO s = new SessionVO();
        s.setId(2200302502L);
        when(chatTools.listSessions(eq(7L), any(LocalDate.class))).thenReturn(List.of(s));
        when(chatTools.getSeatSummary(2200302502L)).thenReturn(summary(2200302502L, 140, 6));

        ActionCardVO card = onlyCard(
                assistant.chat("s1", TODAY, "今晚的座位有连座吗").content());

        assertThat(card.getType())
                .as("没有显式场次 ID 时不该出座位建议卡")
                .isNotEqualTo(ActionCardVO.TYPE_SEAT_SUGGESTION);
    }

    private static ActionCardVO onlyCard(String reply) {
        ReplyCardParser.ParseResult parsed = ReplyCardParser.parse(reply);
        assertThat(parsed.cards()).hasSize(1);
        return parsed.cards().get(0);
    }
}

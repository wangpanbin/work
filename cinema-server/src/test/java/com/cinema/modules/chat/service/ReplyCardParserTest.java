package com.cinema.modules.chat.service;

import com.cinema.modules.chat.service.ReplyCardParser.ParseResult;
import com.cinema.modules.chat.vo.ActionCardVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9 cycle 1 — ReplyCardParser 纯函数测试(spec §6.1 + #16 acceptance).
 *
 * <p>覆盖 7 个关键边界:
 * <ul>
 *   <li>空 / null 输入 → 空 cards, 不动 reply</li>
 *   <li>无 fence 的普通回复 → 空 cards, reply 原样保留</li>
 *   <li>单卡 fence → 解析 1 张卡 + 2 个 followUps, fence 从 reply 剥离</li>
 *   <li>多卡 fence → 解析多张卡, fence 剥离</li>
 *   <li>坏 JSON fence → cards=List.of(), reply 也剥离 fence(UX 优先 — 不让用户看到 broken 块)</li>
 *   <li>缺字段 fence(type/sessionId/seatIndexes 必填任一缺失)→ cards=List.of(), reply 剥离 fence</li>
 *   <li>多 fence 块(LLM 失控)→ 取首个 JSON 解析, 所有 fence 都剥离</li>
 * </ul>
 */
class ReplyCardParserTest {

    @Test
    @DisplayName("空 reply → 空结果, reply 不变")
    void empty_reply_yields_empty() {
        ParseResult r = ReplyCardParser.parse("");
        assertThat(r.cards()).isEmpty();
        assertThat(r.followUps()).isEmpty();
        assertThat(r.strippedReply()).isEqualTo("");
    }

    @Test
    @DisplayName("无 fence 的普通回复 → 空 cards, reply 原样保留")
    void plain_reply_without_fence_unchanged() {
        String reply = "今晚 8 点有 3 场:《流浪地球》19 号厅 42 座。";
        ParseResult r = ReplyCardParser.parse(reply);
        assertThat(r.cards()).isEmpty();
        assertThat(r.followUps()).isEmpty();
        assertThat(r.strippedReply()).isEqualTo(reply);
    }

    @Test
    @DisplayName("单卡 fence → 1 卡 + 2 followUps, fence 剥离")
    void single_card_fence_parsed_and_stripped() {
        String reply = "今晚 8 点《流浪地球》19 号厅还有 42 个可选座位,第 5 排有 2 连座。\n\n"
                + "```json-cards\n"
                + "{\"cards\":[{\"type\":\"SEAT_SUGGESTION\",\"sessionId\":\"1001\","
                + "\"movieTitle\":\"流浪地球\",\"hallName\":\"19 号厅\","
                + "\"startTime\":\"2026-09-21 20:00:00\",\"price\":45.00,"
                + "\"seatIndexes\":[52,53],\"seatDesc\":\"5排4座、5排5座\","
                + "\"totalAmount\":90.00,\"actionLabel\":\"去选座确认\"}],"
                + "\"followUps\":[\"换一个场次\",\"只看 VIP 厅\"]}\n"
                + "```";
        ParseResult r = ReplyCardParser.parse(reply);
        assertThat(r.cards()).hasSize(1);
        ActionCardVO c = r.cards().get(0);
        assertThat(c.getType()).isEqualTo("SEAT_SUGGESTION");
        assertThat(c.getSessionId()).isEqualTo("1001");
        assertThat(c.getMovieTitle()).isEqualTo("流浪地球");
        assertThat(c.getHallName()).isEqualTo("19 号厅");
        assertThat(c.getStartTime()).isEqualTo("2026-09-21 20:00:00");
        assertThat(c.getPrice()).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(c.getSeatIndexes()).containsExactly(52, 53);
        assertThat(c.getSeatDesc()).isEqualTo("5排4座、5排5座");
        assertThat(c.getTotalAmount()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(c.getActionLabel()).isEqualTo("去选座确认");
        assertThat(r.followUps()).containsExactly("换一个场次", "只看 VIP 厅");
        // fence 已剥离,reply 只剩自然语言
        assertThat(r.strippedReply()).doesNotContain("```json-cards");
        assertThat(r.strippedReply()).doesNotContain("SEAT_SUGGESTION");
        assertThat(r.strippedReply()).startsWith("今晚 8 点");
    }

    @Test
    @DisplayName("多卡 fence → 多张卡, fence 剥离")
    void multiple_cards_fence() {
        String reply = "找到 2 场可选:\n\n"
                + "```json-cards\n"
                + "{\"cards\":["
                + "{\"type\":\"SEAT_SUGGESTION\",\"sessionId\":\"1001\",\"movieTitle\":\"A\",\"hallName\":\"1号厅\","
                + "\"startTime\":\"2026-09-21 20:00:00\",\"price\":45.00,\"seatIndexes\":[10,11],"
                + "\"totalAmount\":90.00,\"actionLabel\":\"去选\"},"
                + "{\"type\":\"SEAT_SUGGESTION\",\"sessionId\":\"1002\",\"movieTitle\":\"B\",\"hallName\":\"2号厅\","
                + "\"startTime\":\"2026-09-21 21:00:00\",\"price\":55.00,\"seatIndexes\":[20,21],"
                + "\"totalAmount\":110.00,\"actionLabel\":\"去选\"}],"
                + "\"followUps\":[]}\n"
                + "```";
        ParseResult r = ReplyCardParser.parse(reply);
        assertThat(r.cards()).hasSize(2);
        assertThat(r.cards().get(0).getSessionId()).isEqualTo("1001");
        assertThat(r.cards().get(1).getSessionId()).isEqualTo("1002");
        assertThat(r.followUps()).isEmpty();
        assertThat(r.strippedReply()).doesNotContain("```json-cards");
    }

    @Test
    @DisplayName("坏 JSON fence → cards 空, fence 仍剥离(UX 优先)")
    void bad_json_fence_yields_empty_and_stripped() {
        String reply = "这里是回答正文...\n\n"
                + "```json-cards\n"
                + "{not even valid json}\n"
                + "```";
        ParseResult r = ReplyCardParser.parse(reply);
        assertThat(r.cards()).isEmpty();
        assertThat(r.followUps()).isEmpty();
        // 坏 JSON 也要剥离 fence(不让用户看到 broken 块)
        assertThat(r.strippedReply()).doesNotContain("```json-cards");
        assertThat(r.strippedReply()).doesNotContain("not even valid json");
        assertThat(r.strippedReply()).isEqualTo("这里是回答正文...");
    }

    @Test
    @DisplayName("缺必填字段 fence(type/sessionId/seatIndexes 任一缺失)→ cards 空, fence 剥离")
    void missing_required_field_fence_yields_empty() {
        // 缺 seatIndexes
        String reply = "回答\n```json-cards\n"
                + "{\"cards\":[{\"type\":\"SEAT_SUGGESTION\",\"sessionId\":\"1001\",\"movieTitle\":\"X\"}],"
                + "\"followUps\":[]}\n```";
        ParseResult r = ReplyCardParser.parse(reply);
        assertThat(r.cards()).isEmpty();
        assertThat(r.strippedReply()).doesNotContain("```json-cards");

        // 缺 type
        String reply2 = "```json-cards\n"
                + "{\"cards\":[{\"sessionId\":\"1001\",\"seatIndexes\":[1]}],\"followUps\":[]}\n```";
        ParseResult r2 = ReplyCardParser.parse(reply2);
        assertThat(r2.cards()).isEmpty();

        // 缺 sessionId
        String reply3 = "```json-cards\n"
                + "{\"cards\":[{\"type\":\"X\",\"seatIndexes\":[1]}],\"followUps\":[]}\n```";
        ParseResult r3 = ReplyCardParser.parse(reply3);
        assertThat(r3.cards()).isEmpty();
    }

    @Test
    @DisplayName("多个 fence 块(LLM 失控)→ 首个可解析优先,所有 fence 剥离")
    void multiple_fences_take_first_valid() {
        String reply = "第一段文字\n"
                + "```json-cards\n"
                + "{not valid}\n"
                + "```\n"
                + "中间文字\n"
                + "```json-cards\n"
                + "{\"cards\":[{\"type\":\"SEAT_SUGGESTION\",\"sessionId\":\"77\","
                + "\"seatIndexes\":[1]}],\"followUps\":[\"好\"]}\n"
                + "```\n"
                + "尾巴";
        ParseResult r = ReplyCardParser.parse(reply);
        assertThat(r.cards()).hasSize(1);
        assertThat(r.cards().get(0).getSessionId()).isEqualTo("77");
        assertThat(r.followUps()).containsExactly("好");
        // 所有 fence 都剥离(包括坏的)
        assertThat(r.strippedReply()).doesNotContain("```json-cards");
        assertThat(r.strippedReply()).doesNotContain("not valid");
        // 文字前后保留
        assertThat(r.strippedReply()).contains("第一段文字");
        assertThat(r.strippedReply()).contains("中间文字");
        assertThat(r.strippedReply()).contains("尾巴");
    }
}
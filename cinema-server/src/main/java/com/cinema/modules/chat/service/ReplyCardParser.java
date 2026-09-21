package com.cinema.modules.chat.service;

import com.cinema.modules.chat.vo.ActionCardVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T9 cycle 1 — ReplyCardParser 纯函数(无依赖,易测).
 *
 * <p>LLM 在 reply 末尾可输出 fenced {@code ```json-cards ... ```} 块,
 * 格式:<pre>{@code
 *   ```json-cards
 *   {"cards":[{...ActionCardVO...}], "followUps":["a","b"]}
 *   ```
 * }</pre>
 *
 * <p>解析规则(spec §6.1 + #16 acceptance):
 * <ul>
 *   <li>空 / null reply → 空结果, reply 不变</li>
 *   <li>无 fence 的普通 reply → 空结果, reply 原样保留</li>
 *   <li>多个 fence 块 → 取首个可解析的(其余 fence 也都从 reply 剥离,UX 干净)</li>
 *   <li>坏 JSON fence → cards=List.of(), fence 仍剥离(不让用户看到 broken 块)</li>
 *   <li>缺必填字段(type/sessionId/seatIndexes 任一缺失或空)→ 该卡丢弃, 不影响其他卡</li>
 *   <li>解析失败用 {@code log.warn} 静默, 不抛给前端(spec §5.4 容错路径)</li>
 * </ul>
 *
 * <p><b>为什么纯函数不在 ChatAssistantService 里:</b> spec §6.1 测试断言 cards 非空需要单测,而
 * {@code ChatAssistantService.chat()} 依赖 LangChain4j 代理, 单测 mock 字符串返 "hello world" 时
 * 永远测不到 cards 提取逻辑. 拆纯函数让契约清晰可测, 同时不污染 ChatAssistantService 的现有 7 个测试.
 */
@Slf4j
public final class ReplyCardParser {

    /** 围栏正则: ```json-cards\n<body>\n``` (multiline,允许 body 跨多行) */
    private static final Pattern FENCE = Pattern.compile(
            "```json-cards\\s*\\n([\\s\\S]*?)\\n```",
            Pattern.MULTILINE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析结果: cards + followUps + 剥离 fence 后的 reply(给前端 UI 渲染用). */
    public record ParseResult(
            List<ActionCardVO> cards,
            List<String> followUps,
            String strippedReply
    ) {}

    private ReplyCardParser() {
    }

    public static ParseResult parse(String reply) {
        if (reply == null || reply.isEmpty()) {
            return new ParseResult(List.of(), List.of(), reply == null ? "" : reply);
        }

        // 1) 找所有 fence, 同时剥离 (无论是否后续解析成功)
        Matcher m = FENCE.matcher(reply);
        StringBuilder stripped = new StringBuilder();
        List<String> bodies = new ArrayList<>();
        int lastEnd = 0;
        while (m.find()) {
            stripped.append(reply, lastEnd, m.start());
            bodies.add(m.group(1));
            lastEnd = m.end();
        }
        stripped.append(reply, lastEnd, reply.length());

        if (bodies.isEmpty()) {
            return new ParseResult(List.of(), List.of(), reply);
        }

        // 2) 多个 body: 试每个, 首个 JSON 解析成功即可
        for (String body : bodies) {
            ParseResult r = tryParseBody(body);
            if (r != null) {
                return new ParseResult(r.cards(), r.followUps(), stripped.toString().trim());
            }
        }
        // 全部失败 → cards 空, fence 已剥离
        return new ParseResult(List.of(), List.of(), stripped.toString().trim());
    }

    /** 单个 body 尝试解析; 失败返 null (caller 继续试下一个). */
    private static ParseResult tryParseBody(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("[chat] ReplyCardParser: JSON parse failed: {}", e.getMessage());
            return null;
        }
        if (!root.isObject()) {
            log.warn("[chat] ReplyCardParser: root not an object");
            return null;
        }
        JsonNode cardsNode = root.path("cards");
        JsonNode followUpsNode = root.path("followUps");
        if (!cardsNode.isArray() || !followUpsNode.isArray()) {
            log.warn("[chat] ReplyCardParser: cards/followUps must be arrays");
            return null;
        }

        List<ActionCardVO> cards = new ArrayList<>();
        for (JsonNode cn : cardsNode) {
            ActionCardVO vo = tryParseCard(cn);
            if (vo != null) cards.add(vo);
        }

        List<String> followUps = new ArrayList<>();
        for (JsonNode fn : followUpsNode) {
            if (fn.isTextual()) followUps.add(fn.asText());
        }
        return new ParseResult(List.copyOf(cards), List.copyOf(followUps), null);
    }

    /** 单卡解析; 缺必填字段返 null (caller 跳过). */
    private static ActionCardVO tryParseCard(JsonNode cn) {
        if (!cn.isObject()) return null;
        String type = textOrNull(cn, "type");
        String sessionId = textOrNull(cn, "sessionId");
        JsonNode seatIdx = cn.path("seatIndexes");
        if (type == null || sessionId == null) return null;
        if (!seatIdx.isArray() || seatIdx.isEmpty()) return null;

        List<Integer> indexes = new ArrayList<>();
        for (JsonNode idx : seatIdx) {
            if (idx.canConvertToInt()) indexes.add(idx.asInt());
        }
        if (indexes.isEmpty()) return null;

        return ActionCardVO.builder()
                .type(type)
                .sessionId(sessionId)
                .movieTitle(textOrNull(cn, "movieTitle"))
                .hallName(textOrNull(cn, "hallName"))
                .startTime(textOrNull(cn, "startTime"))
                .price(decimalOrNull(cn, "price"))
                .seatIndexes(indexes)
                .seatDesc(textOrNull(cn, "seatDesc"))
                .totalAmount(decimalOrNull(cn, "totalAmount"))
                .actionLabel(textOrNull(cn, "actionLabel"))
                .build();
    }

    private static String textOrNull(JsonNode obj, String field) {
        JsonNode n = obj.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static BigDecimal decimalOrNull(JsonNode obj, String field) {
        JsonNode n = obj.path(field);
        if (n.isMissingNode() || n.isNull() || !n.isNumber()) return null;
        // 用 asText 保留原始表示,避免 double 转换精度问题(45.00 → 45.0 这种)
        return new BigDecimal(n.asText());
    }
}
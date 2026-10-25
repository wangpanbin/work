package com.cinema.modules.chat.service;

import com.cinema.modules.chat.order.OrderProposalLimits;
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
 * T9 cycle 1 — ReplyCardParser 纯函数(无依赖,易测)。
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
 * 永远测不到 cards 提取逻辑. 拆纯函数让契约清晰可测, 同时不污染 ChatAssistantService 的现有 7 个测试。
 */
@Slf4j
public final class ReplyCardParser {

    /** 围栏正则: ```json-cards\n<body>\n``` (multiline,允许 body 跨多行) */
    private static final Pattern FENCE = Pattern.compile(
            "```json-cards\\s*\\n([\\s\\S]*?)\\n```",
            Pattern.MULTILINE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 解析结果: cards + followUps + 剥离 fence 后的 reply(给前端 UI 渲染用)。 */
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

    /** 单个 body 尝试解析; 失败返 null (caller 继续试下一个)。 */
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

    /**
     * 单卡解析; 缺必填字段返 null (caller 跳过)。
     *
     * <p><b>必填规则按卡型分派</b>(ADR-0004):
     * <ul>
     *   <li>既有卡型(如 SEAT_SUGGESTION): {@code type + sessionId + seatIndexes[]} 全必填,
     *       规则与改造前<b>一字未改</b>。</li>
     *   <li>{@code ORDER_PROPOSAL}(一键下单确认卡): {@code type + sessionId + count} 必填,
     *       {@code seatIndexes} 降级为<b>可选提示</b> —— 座位最终由服务端重读位图决定
     *       (ADR-0004 决策 D8), 模型没给座位也不该让整张卡作废。</li>
     * </ul>
     */
    private static ActionCardVO tryParseCard(JsonNode cn) {
        if (!cn.isObject()) return null;
        String type = textOrNull(cn, "type");
        if (type == null) return null;
        String sessionId = textOrNull(cn, "sessionId");

        List<Integer> indexes = new ArrayList<>();
        JsonNode seatIdx = cn.path("seatIndexes");
        if (seatIdx.isArray()) {
            for (JsonNode idx : seatIdx) {
                if (idx.canConvertToInt()) indexes.add(idx.asInt());
            }
        }

        Integer count = null;
        if (ActionCardVO.TYPE_ORDER_GUIDE.equals(type)) {
            // 引导卡(v0.4.0 IT-06): 只要 type + action, **不需要** sessionId / seatIndexes ——
            // 它不带任何可提交载荷, 只带「去订单页做什么」的跳转信息。
            String action = textOrNull(cn, "action");
            if (!ActionCardVO.GUIDE_CANCEL.equals(action)
                    && !ActionCardVO.GUIDE_REFUND.equals(action)) {
                return null;
            }
            return ActionCardVO.builder()
                    .type(type)
                    .guideAction(action)
                    .guideRoute("/orders")
                    .guideHint(textOrNull(cn, "hint"))
                    .actionLabel(textOrNull(cn, "actionLabel"))
                    // 展示信息: 系统提示词让模型输出影片名/影厅/场次时间,
                    // 这里必须带过去 —— 丢掉的话引导卡只能显示通用兜底标题,
                    // 用户看到的是「订单操作指引」而不是「哪部电影的订单」。
                    .movieTitle(textOrNull(cn, "movieTitle"))
                    .hallName(textOrNull(cn, "hallName"))
                    .startTime(textOrNull(cn, "startTime"))
                    .seatDesc(textOrNull(cn, "seatDesc"))
                    .build();
        }
        if (ActionCardVO.TYPE_ORDER_PROPOSAL.equals(type)) {
            // 一键下单卡: count 必填且在 [1,4]; seatIndexes 只是提示, 可空
            count = intOrNull(cn, "count");
            if (sessionId == null || count == null || count < 1 || count > OrderProposalLimits.MAX_SEATS) {
                return null;
            }
        } else {
            // 既有卡型: 规则不变
            if (sessionId == null || indexes.isEmpty()) return null;
        }

        return ActionCardVO.builder()
                .type(type)
                .sessionId(sessionId)
                .movieTitle(textOrNull(cn, "movieTitle"))
                .hallName(textOrNull(cn, "hallName"))
                .startTime(textOrNull(cn, "startTime"))
                .price(decimalOrNull(cn, "price"))
                .seatIndexes(indexes.isEmpty() ? null : indexes)
                .seatDesc(textOrNull(cn, "seatDesc"))
                .totalAmount(decimalOrNull(cn, "totalAmount"))
                .actionLabel(textOrNull(cn, "actionLabel"))
                .count(count)
                .build();
    }

    /** 读整数; 缺失 / 非数值 / 不可转 int 时返 null。 */
    private static Integer intOrNull(JsonNode obj, String field) {
        JsonNode n = obj.path(field);
        if (n.isMissingNode() || n.isNull() || !n.canConvertToInt()) return null;
        return n.asInt();
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
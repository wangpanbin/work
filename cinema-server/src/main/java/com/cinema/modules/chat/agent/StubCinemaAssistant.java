package com.cinema.modules.chat.agent;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.modules.chat.tools.ChatTools;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.session.vo.SessionVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假 LLM —— 确定性的对话回复, 只替换「模型决策」这一步。
 *
 * <p><b>它替换什么、不替换什么</b>:
 * <ul>
 *   <li><b>替换</b>: 真实 LLM 的自由发挥(它爱用 markdown 表格 + 反问, 出卡与否看心情)。</li>
 *   <li><b>不替换</b>: 卡片解析({@code ReplyCardParser})、提案装配({@code OrderProposalAssembler})、
 *       座位重读与价格核算({@code OrderProposalService})、前端渲染 —— 全是生产代码,
 *       走的是与真实 LLM <b>完全相同</b>的那条路。</li>
 * </ul>
 *
 * <p><b>为什么需要它</b>: 一键下单确认卡(AC-20 那条浏览器链路)此前是「看 LLM 心情」——
 * S22 在真实 LLM 下可能出卡也可能不出, 出不出取决于模型当天怎么发挥, 于是这条验收
 * 长期停在 SKIP。加了它之后, 浏览器回归能确定性地覆盖「浮窗 → 下单卡 → 确认下单 → 订单建成」。
 *
 * <p><b>它不是 mock</b>: stub 走的是真实的只读工具({@code searchMovies} / {@code listSessions} /
 * {@code getSeatSummary}), 挑的是真实有余座的场次, 出的是真实能被解析器吃进去的
 * {@code ```json-cards} 块 —— 契约跟真 LLM 一样, 契约变了它一样会炸。
 *
 * <p><b>它刻意不给下单卡的 {@code seatIndexes}</b>: 服务端会重读实时位图自己挑连座
 * (ADR-0004 决策 D8), 所以下单卡只给 {@code sessionId + count}。
 * 这条路径正好把「卡里没有座位索引」也一起覆盖了。
 *
 * <p><b>座位建议卡(SEAT_SUGGESTION)是另一条路</b>: 用户显式给了场次 ID 又在问
 * 座位/位置/布局时, stub 给的是 {@code sessionId + seatIndexes} 的**座位建议卡** ——
 * 它的前端动作是跳选座页({@code /seat/{sessionId}?preselect=...}), 不是一键下单。
 * 那个场景原本只能用真 LLM 撞运气出卡, 浏览器回归覆盖不到。
 *
 * <p><b>启用方式</b>: {@code cinema.chat.provider=stub}(见 {@code application.yml});
 * 默认仍是 {@code deepseek}。它<b>不注册任何写工具</b>, ADR-0002 的只读边界一字未动。
 */
@Slf4j
public class StubCinemaAssistant implements CinemaAssistant {

    /** 消息里显式给的场次 ID(6~12 位数字)。 */
    private static final Pattern SESSION_ID = Pattern.compile("(?<!\\d)(\\d{6,12})(?!\\d)");

    /** 消息里明说的张数:「3 张」「4 个」「2 人」。 */
    private static final Pattern COUNT_HINT = Pattern.compile("(\\d{1,2})\\s*(?:张|个|人|位)");

    /**
     * 消息里在问「座位/位置/布局」—— 触发座位建议卡(SEAT_SUGGESTION)。
     *
     * <p>⚠️ **「连座」不算**:「帮我推荐今晚的场次,要两个连座」(E2E S22 那句)里
     * 没有「座位」二字, 必须继续走下单卡那条路 —— 那条链路是「点确认下单 → 订单建成」,
     * 被吸走就等于把 S22 的验收弄没了。
     */
    private static final Pattern SEAT_QUESTION = Pattern.compile("座位|位置|布局");

    /** 提示词里给模型的「当前日期」上下文, 用来定位今天。 */
    private static final Pattern TODAY = Pattern.compile("今天是\\s*(\\d{4}-\\d{2}-\\d{2})");

    /** 卡型枚举 —— 与 {@code ActionCardVO} 的常量同名同值。 */
    private static final String TYPE_SEAT_SUGGESTION = "SEAT_SUGGESTION";

    /** 卡里的开场时间格式(前端展示用)。 */
    private static final DateTimeFormatter START_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 卡片 JSON 序列化(复用 Spring Boot 自带的 Jackson)。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 缺省张数 —— 用户没说时按 2 张出卡(与提示词里的约定一致)。 */
    private static final int DEFAULT_COUNT = 2;

    /** 最多翻几个热映影片去找场次(够了, 再多只是浪费时间)。 */
    private static final int MAX_MOVIES = 5;

    private final ChatTools chatTools;

    public StubCinemaAssistant(ChatTools chatTools) {
        this.chatTools = chatTools;
    }

    @Override
    public Result<String> chat(String chatSessionId, String currentDate, String userMessage) {
        String msg = userMessage == null ? "" : userMessage;
        int count = resolveCount(msg);

        // 座位建议分支(SEAT_SUGGESTION): 显式场次 ID + 在问座位/位置/布局。
        // 这张卡的前端动作是**跳选座页**(不是一键下单), 带 seatIndexes,
        // 前端拼成 /seat/{sessionId}?preselect=...。
        // ⚠️ 必须排在下单卡那条路**前面**, 但触发条件要窄 ——
        //    「帮我推荐今晚的场次,要两个连座」(E2E S22)不含场次 ID 也不含「座位」, 天然不命中。
        if (asksAboutSeats(msg)) {
            Long seatSessionId = resolveExplicitSessionId(msg, count, false);
            if (seatSessionId != null) {
                return seatSuggestion(seatSessionId, count);
            }
        }

        Long sessionId = resolveSessionId(msg, currentDate, count);
        if (sessionId == null) {
            return reply("没找到还有余座的场次。你可以告诉我影片名或场次 ID, 我再帮你挑连座。", 12, 18);
        }

        Map<String, Object> summary = safeSummary(sessionId);
        if (summary == null) {
            return reply("这个场次暂时查不到座位信息, 换个场次试试。", 12, 18);
        }
        String movieTitle = str(summary.get("movieTitle"));
        String hallName = str(summary.get("hallName"));
        String startTime = str(summary.get("startTime"));

        // ⚠️ 刻意**不带** seatIndexes —— 服务端会重读实时位图自己挑连座(ADR-0004 D8)。
        String body = "给你挑了《" + movieTitle + "》" + hallName + "(" + startTime + ")的 "
                + count + " 个连座, 点下面的卡片确认:\n\n"
                + "```json-cards\n"
                + "{\"cards\":[{\"type\":\"ORDER_PROPOSAL\",\"sessionId\":\"" + sessionId
                + "\",\"count\":" + count + "}],"
                + "\"followUps\":[\"换个场次\",\"改成 1 张\"]}\n"
                + "```\n";
        return reply(body, 30, 42);
    }

    // ------------------------------------------------------------------ 座位建议卡

    /**
     * 出一张座位建议卡(SEAT_SUGGESTION)。
     *
     * <p>与下单卡(ORDER_PROPOSAL)的区别:
     * <ul>
     *   <li>下单卡的按钮是「确认下单」→ 走对话内一键下单(ADR-0004);</li>
     *   <li>座位建议卡的按钮是「去选座」→ 跳 {@code /seat/{sessionId}?preselect=...}。</li>
     * </ul>
     *
     * <p>⚠️ **取不到连座就不出卡** —— 给一张跳过去没座的卡比不给更糟。
     * 那时退回复一句实话, 让用户换个场次或换个时间。
     */
    private Result<String> seatSuggestion(long sessionId, int count) {
        List<Integer> seats = safeFindContiguous(sessionId, count);
        if (seats == null || seats.isEmpty()) {
            return reply("这个场次暂时没有 " + count + " 个连座, 换个场次或换个时间试试。", 12, 18);
        }

        Map<String, Object> summary = safeSummary(sessionId);
        if (summary == null) {
            return reply("这个场次暂时查不到座位信息, 换个场次试试。", 12, 18);
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", TYPE_SEAT_SUGGESTION);
        // 雪花 ID 必须走字符串 —— 前端 URL 用 String
        card.put("sessionId", String.valueOf(sessionId));
        card.put("seatIndexes", seats);
        putIfPresent(card, "movieTitle", summary.get("movieTitle"));
        putIfPresent(card, "hallName", summary.get("hallName"));
        putIfPresent(card, "startTime", startTimeText(summary.get("startTime")));
        if (summary.get("price") != null) {
            card.put("price", summary.get("price"));
        }

        String cardJson;
        try {
            cardJson = MAPPER.writeValueAsString(card);
        } catch (Exception e) {
            log.warn("[chat-stub] 序列化座位建议卡失败: {}", e.getMessage());
            return reply("这个场次的座位建议生成失败了, 换个场次试试。", 12, 18);
        }

        String body = "《" + str(summary.get("movieTitle")) + "》"
                + str(summary.get("hallName")) + " 给你留了 " + seats.size() + " 个连座, 点下面的卡片去选座:\n\n"
                + "```json-cards\n"
                + "{\"cards\":[" + cardJson + "],"
                + "\"followUps\":[\"换个场次\",\"改成 1 个\"]}\n"
                + "```\n";
        return reply(body, 26, 38);
    }

    /** 消息里是不是在问座位/位置/布局。 */
    private boolean asksAboutSeats(String msg) {
        return SEAT_QUESTION.matcher(msg).find();
    }

    /** 找连座 —— 工具抛异常时当作「没有连座」, 不让整个对话挂掉。 */
    private List<Integer> safeFindContiguous(long sessionId, int count) {
        try {
            return chatTools.findContiguousSeats(sessionId, count, null);
        } catch (RuntimeException e) {
            log.warn("[chat-stub] 查场次 {} 的连座失败: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 往卡片里塞一个非空字段。 */
    private static void putIfPresent(Map<String, Object> card, String key, Object value) {
        String text = str(value);
        if (!text.isBlank()) {
            card.put(key, text);
        }
    }

    /** 开场时间 → {@code yyyy-MM-dd HH:mm:ss}(拿不到就原样返回字符串)。 */
    private static String startTimeText(Object startTime) {
        if (startTime instanceof LocalDateTime dt) {
            return dt.format(START_TIME);
        }
        return startTime == null ? null : String.valueOf(startTime);
    }

    // ------------------------------------------------------------------ 场次

    /**
     * 挑一个目标场次。优先级:
     * <ol>
     *   <li>消息里显式给的场次 ID(先校验它确实是个有余座的场次)</li>
     *   <li>否则在「今天/明天/后天」里翻热映影片, 找第一个有余座且有连座的场次</li>
     * </ol>
     */
    private Long resolveSessionId(String msg, String currentDate, int count) {
        Long explicit = resolveExplicitSessionId(msg, count, true);
        if (explicit != null) {
            return explicit;
        }
        return pickSessionWithRoom(currentDate, count);
    }

    /**
     * 消息里显式给的场次 ID。
     *
     * @param requireRoom true → 还得「余座够且有连座」(下单卡那条路用);
     *                   false → 只要「是个真场次、还没开场」(座位建议那条路用 ——
     *                   没有连座时要给用户一句实话, 而不是偷偷换成别的场次)
     */
    private Long resolveExplicitSessionId(String msg, int count, boolean requireRoom) {
        Matcher m = SESSION_ID.matcher(msg);
        while (m.find()) {
            long candidate;
            try {
                candidate = Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (requireRoom ? hasRoom(candidate, count) : isPlayable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 场次存在、还没开场、还剩至少一个座位。 */
    private boolean isPlayable(long sessionId) {
        Map<String, Object> summary = safeSummary(sessionId);
        if (summary == null) {
            return false;
        }
        if (hasStarted(summary.get("startTime"))) {
            return false;
        }
        return intOf(summary.get("available")) > 0;
    }

    /** 在最近三天里翻热映影片, 找第一个「余座够且有连座」的场次。 */
    private Long pickSessionWithRoom(String currentDate, int count) {
        LocalDate today = parseToday(currentDate);
        Page<Movie> page;
        try {
            page = chatTools.searchMovies(null, null, null);
        } catch (RuntimeException e) {
            log.warn("[chat-stub] 查热映影片失败: {}", e.getMessage());
            return null;
        }
        if (page == null || page.getRecords() == null) {
            return null;
        }
        int checked = 0;
        for (Movie movie : page.getRecords()) {
            if (checked++ >= MAX_MOVIES) {
                break;
            }
            for (int day = 0; day < 3; day++) {
                List<SessionVO> sessions;
                try {
                    sessions = chatTools.listSessions(movie.getId(), today.plusDays(day));
                } catch (RuntimeException e) {
                    log.warn("[chat-stub] 查影片 {} 的场次失败: {}", movie.getId(), e.getMessage());
                    continue;
                }
                if (sessions == null) {
                    continue;
                }
                for (SessionVO s : sessions) {
                    if (s.getId() == null) {
                        continue;
                    }
                    // 只要在售的(status=1), 且还没开场
                    if (s.getStatus() != null && s.getStatus() != 1) {
                        continue;
                    }
                    if (s.getStartTime() != null && !s.getStartTime().isAfter(LocalDateTime.now())) {
                        continue;
                    }
                    if (hasRoom(s.getId(), count)) {
                        return s.getId();
                    }
                }
            }
        }
        return null;
    }

    /** 这个场次还剩几个座, 够不够 {@code count} 个连座, 而且**还没开场**。 */
    private boolean hasRoom(long sessionId, int count) {
        Map<String, Object> summary = safeSummary(sessionId);
        if (summary == null) {
            return false;
        }
        // ⚠️ 必须排除**已开场**的场次 —— 服务端下单时会拒("场次已开场,无法购票"),
        // 那时候卡片已经被装配器丢掉了, 用户只看到一句没头没尾的说明。
        // (实测踩到: stub 一上来就挑了当天 10:00 的场次, 而那会儿已经是下午。)
        if (hasStarted(summary.get("startTime"))) {
            return false;
        }
        int available = intOf(summary.get("available"));
        if (available < count) {
            return false;
        }
        Object longest = summary.get("longestContiguousLength");
        // 没有 longest 字段说明一个连座都没有 —— 那种场次不能出连座卡。
        return longest != null && intOf(longest) >= count;
    }

    /** 场次是否已经开场。 */
    private static boolean hasStarted(Object startTime) {
        if (startTime instanceof LocalDateTime dt) {
            return !dt.isAfter(LocalDateTime.now());
        }
        return false;
    }

    private Map<String, Object> safeSummary(long sessionId) {
        try {
            return chatTools.getSeatSummary(sessionId);
        } catch (RuntimeException e) {
            log.warn("[chat-stub] 查场次 {} 座位摘要失败: {}", sessionId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 杂项

    /** 张数: 消息里明说的优先, 没明说按 {@link #DEFAULT_COUNT}。 */
    private int resolveCount(String msg) {
        Matcher m = COUNT_HINT.matcher(msg);
        if (m.find()) {
            int n = intOf(m.group(1));
            if (n >= 1 && n <= 4) {
                return n;
            }
        }
        return DEFAULT_COUNT;
    }

    private LocalDate parseToday(String currentDate) {
        if (currentDate != null) {
            Matcher m = TODAY.matcher(currentDate);
            if (m.find()) {
                try {
                    return LocalDate.parse(m.group(1));
                } catch (RuntimeException ignored) {
                    // 落回今天
                }
            }
        }
        return LocalDate.now();
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 造一个带 token 用量的 {@link Result} —— 看板要靠它算成本, 不能给 null。 */
    private static Result<String> reply(String content, int in, int out) {
        return new Result<>(content, new TokenUsage(in, out), null, null, null);
    }
}

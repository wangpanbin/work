package com.cinema.modules.chat.service;

import com.cinema.modules.chat.entity.ChatAssistantLog;
import com.cinema.modules.chat.mapper.ChatAssistantLogMapper;
import com.cinema.modules.chat.vo.ChatAssistantStatsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对话助手「写审计 + 用量」日志服务(v0.4.0,IT-05 + IT-11)。
 *
 * <p><b>最要紧的一条语义:审计写入永远不能影响主流程。</b>
 * 审计表写失败(表没建、DB 抖动、字段超长)绝不能让用户的下单失败 ——
 * 所有写库都在 {@link #safeInsert} 里被 try/catch 吞掉,失败只记 error 日志。
 * 反过来,主流程挂了(LLM 报错、座位冲突)恰恰是最该留下审计痕迹的时候,
 * 所以调用方要在**拿到结果之后**才来记,而不是只在成功路径上记。
 *
 * <p><b>成本口径</b>:单价来自配置({@code cinema.chat.cost.*}),单位是
 * 「美分(USD)/ 百万 token」,默认取 DeepSeek {@code deepseek-flash} 的
 * 非高峰价(取价日期 2026-10-25,来源 https://api-docs.deepseek.com/quick_start/pricing)。
 * <b>这是估算,不是财务口径</b> —— DeepSeek 会调价且分峰谷时段,单价需人工维护。
 * 单价未配置(配成 0)时成本记 null,不记 0。
 */
@Slf4j
@Service
public class ChatAssistantLogService {

    /** 每百万 token 的输入单价(美分)。配 0 = 未配置单价 → 成本记 null。 */
    private final int promptPricePerMillionCents;

    /** 每百万 token 的输出单价(美分)。配 0 = 未配置单价 → 成本记 null。 */
    private final int completionPricePerMillionCents;

    private final ChatAssistantLogMapper mapper;

    public ChatAssistantLogService(ChatAssistantLogMapper mapper,
                                   @Value("${cinema.chat.cost.prompt-per-million-cents:15}")
                                   int promptPricePerMillionCents,
                                   @Value("${cinema.chat.cost.completion-per-million-cents:60}")
                                   int completionPricePerMillionCents) {
        this.mapper = mapper;
        this.promptPricePerMillionCents = promptPricePerMillionCents;
        this.completionPricePerMillionCents = completionPricePerMillionCents;
    }

    /**
     * 记一次对话调用(用量口径)。
     *
     * @param sessionId    会话 ID
     * @param userId       用户 ID,匿名为 null
     * @param latencyMs    服务端处理耗时;拿不到就传 null
     * @param promptTokens 输入 token;**用量未知传 null,不要传 0**
     * @param completionTokens 输出 token;**用量未知传 null,不要传 0**
     * @param result       结果码
     */
    public void recordChat(String sessionId, Long userId, Integer latencyMs,
                           Integer promptTokens, Integer completionTokens, String result) {
        ChatAssistantLog row = new ChatAssistantLog();
        row.setChatSessionId(sessionId == null ? "" : sessionId);
        row.setUserId(userId);
        row.setKind(ChatAssistantLog.KIND_CHAT);
        // 非写事件 → 不适用,必须是 null 而不是 0(0 是「事故」)
        row.setConfirmedByUser(null);
        row.setResult(result == null ? ChatAssistantLog.RESULT_ERROR : result);
        row.setLatencyMs(latencyMs);
        applyUsage(row, promptTokens, completionTokens);
        safeInsert(row);
    }

    /**
     * 记一次「确认下单」尝试(写审计口径)。
     *
     * <p><b>{@code confirmedByUser} 只允许两种来源</b>:
     * {@code 1} = 用户在确认卡上点了「确认下单」(唯一合法的写触发点);
     * {@code 0} = 未经用户确认的写 —— 那是事故,红线查询会数出来。
     * 将来新增任何写入口,都必须显式传 {@code 1},否则闸门会红。
     *
     * @param confirmedByUser 1 = 用户显式确认;0 = 事故
     */
    public void recordWriteConfirm(String sessionId, Long userId, String proposalId, String orderNo,
                                    String result, int confirmedByUser, Integer latencyMs) {
        ChatAssistantLog row = new ChatAssistantLog();
        row.setChatSessionId(sessionId == null ? "" : sessionId);
        row.setUserId(userId);
        row.setKind(ChatAssistantLog.KIND_WRITE_CONFIRM);
        row.setConfirmedByUser(confirmedByUser);
        row.setProposalId(proposalId);
        row.setOrderNo(orderNo);
        row.setResult(result == null ? ChatAssistantLog.RESULT_ERROR : result);
        row.setLatencyMs(latencyMs);
        // 写确认这一步本身不调 LLM,token 恒为 null(用量未知)
        applyUsage(row, null, null);
        safeInsert(row);
    }

    /**
     * 近 N 天的汇总统计(看板四项指标 + 红线指标)。
     */
    public ChatAssistantStatsVO stats(int days) {
        int window = days <= 0 ? 7 : days;
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        ChatAssistantStatsVO vo = new ChatAssistantStatsVO();
        vo.setDays(window);
        vo.setSince(since);

        Map<String, Object> row = mapper.summarize(since);
        long total = asLong(row, "total");
        long failed = asLong(row, "failed");
        long unconfirmed = asLong(row, "unconfirmed_writes");
        long writeAttempts = asLong(row, "write_attempts");
        long writeSuccess = asLong(row, "write_success");

        vo.setTotalCalls(total);
        vo.setFailedCalls(failed);
        vo.setFailureRate(total == 0 ? 0d : round4((double) failed / total));
        vo.setSessions(asLong(row, "sessions"));
        vo.setUsers(asLong(row, "users"));
        vo.setPromptTokens(asLong(row, "prompt_tokens"));
        vo.setCompletionTokens(asLong(row, "completion_tokens"));
        vo.setCostCents(asLong(row, "cost_cents"));
        vo.setWriteAttempts(writeAttempts);
        vo.setWriteSuccess(writeSuccess);
        vo.setUnconfirmedWrites(unconfirmed);
        vo.setP95LatencyMs(mapper.p95LatencyMs(since));

        List<Map<String, Object>> byDay = mapper.callsByDay(since);
        List<Map<String, Object>> daily = new java.util.ArrayList<>(byDay.size());
        for (Map<String, Object> dayRow : byDay) {
            // 显式建 Map: Map.of 的类型推断会推出带一堆交叉边界的通配符,
            // 赋值给 List<Map<String,Object>> 时编译不过。
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("day", String.valueOf(dayRow.get("day")));
            item.put("calls", asLong(dayRow, "calls"));
            daily.add(item);
        }
        vo.setDailyCalls(daily);
        return vo;
    }

    /**
     * 红线指标 AC-13:未经用户显式确认的写操作数。<b>必须恒为 0。</b>
     */
    public int countUnconfirmedWrites() {
        return mapper.countUnconfirmedWrites();
    }

    /**
     * 审计表是否已建(启动期探活)。
     */
    public boolean tableExists() {
        try {
            return mapper.countTableExists() > 0;
        } catch (Exception e) {
            log.warn("[chat-log] 探测 chat_assistant_log 表失败(可能是迁移没跑): {}", e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 写库 —— **绝不影响主流程**。
     */
    private void safeInsert(ChatAssistantLog row) {
        try {
            mapper.insert(row);
        } catch (Exception e) {
            log.error("[chat-log] 写审计日志失败(kind={}, session={}): {}",
                    row.getKind(), row.getChatSessionId(), e.getMessage(), e);
        }
    }

    /** 用量与成本。用量未知 → null(不是 0);单价未配置 → 成本 null。 */
    private void applyUsage(ChatAssistantLog row, Integer promptTokens, Integer completionTokens) {
        row.setPromptTokens(promptTokens);
        row.setCompletionTokens(completionTokens);
        if (promptTokens == null && completionTokens == null) {
            row.setCostCents(null);
            return;
        }
        if (promptPricePerMillionCents <= 0 && completionPricePerMillionCents <= 0) {
            // 单价未配置 → 不编造 0 成本
            row.setCostCents(null);
            return;
        }
        long cost = scaleToCents(promptTokens, promptPricePerMillionCents)
                + scaleToCents(completionTokens, completionPricePerMillionCents);
        row.setCostCents(cost);
    }

    /**
     * token 数 → 美分。整数运算 + 四舍五入,**不用浮点**(钱的计算不碰浮点)。
     */
    private static long scaleToCents(Integer tokens, int pricePerMillionCents) {
        if (tokens == null || tokens <= 0 || pricePerMillionCents <= 0) {
            return 0L;
        }
        return (tokens.longValue() * pricePerMillionCents + 500_000L) / 1_000_000L;
    }

    private static long asLong(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object v = row.get(key);
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }
}

package com.cinema.modules.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cinema.modules.chat.entity.ChatAssistantLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 对话助手审计与用量日志的 Mapper。
 *
 * <p>统计类查询都带 {@code since} 下界(近 N 天),索引 {@code idx_kind_created}
 * 与 {@code idx_created} 覆盖这两个过滤维度。
 */
@Mapper
public interface ChatAssistantLogMapper extends BaseMapper<ChatAssistantLog> {

    /**
     * 表是否存在(启动期探活用)。
     *
     * <p>{@code sql/08_chat_assistant_log.sql} 没跑过时返回 0 —— 审计写入会被
     * service 层 try/catch 吞掉(不影响下单),但管理端看板会是空的,启动日志里
     * 会有一条 warn 提醒去跑迁移。
     */
    @Select("SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_schema = DATABASE() AND table_name = 'chat_assistant_log'")
    int countTableExists();

    /**
     * 汇总统计:调用量 / 失败数 / 未确认写数 / token 合计 / 成本合计。
     *
     * <p>{@code unconfirmed_writes} 就是红线指标 AC-13 的分子 ——
     * 它必须恒为 0。
     */
    @Select("SELECT COUNT(*) AS total, "
            + "SUM(CASE WHEN result = 'ERROR' THEN 1 ELSE 0 END) AS failed, "
            + "SUM(CASE WHEN kind = 'WRITE_CONFIRM' AND confirmed_by_user = 0 THEN 1 ELSE 0 END) AS unconfirmed_writes, "
            + "SUM(CASE WHEN kind = 'WRITE_CONFIRM' THEN 1 ELSE 0 END) AS write_attempts, "
            + "SUM(CASE WHEN kind = 'WRITE_CONFIRM' AND result = 'OK' THEN 1 ELSE 0 END) AS write_success, "
            + "SUM(COALESCE(prompt_tokens, 0)) AS prompt_tokens, "
            + "SUM(COALESCE(completion_tokens, 0)) AS completion_tokens, "
            // ⚠️ 这里**不能** COALESCE: 单价未配置时每行的 cost_cents 是 null,
            // COALESCE 会把「未知」压成 0 → 看板永远显示 $0.0000,
            // 而「未配置单价」那个状态就再也显示不出来了。
            + "SUM(cost_cents) AS cost_cents, "
            + "COUNT(DISTINCT NULLIF(chat_session_id, '')) AS sessions, "
            + "COUNT(DISTINCT user_id) AS users "
            + "FROM chat_assistant_log WHERE created_at >= #{since}")
    Map<String, Object> summarize(@Param("since") LocalDateTime since);

    /**
     * P95 延迟(毫秒)。
     *
     * <p>MySQL 8 没有 {@code PERCENTILE_CONT},用窗口函数取第
     * {@code ceil(n * 0.95)} 小的非空延迟值。没有样本时返回 null。
     */
    @Select("SELECT latency_ms FROM ("
            + "  SELECT latency_ms, ROW_NUMBER() OVER (ORDER BY latency_ms) AS rn, COUNT(*) OVER () AS cnt"
            + "  FROM chat_assistant_log WHERE created_at >= #{since} AND latency_ms IS NOT NULL"
            + ") t WHERE t.rn = GREATEST(1, CEIL(t.cnt * 0.95)) LIMIT 1")
    Integer p95LatencyMs(@Param("since") LocalDateTime since);

    /**
     * 红线指标 AC-13:未经用户显式确认的写操作数。**必须恒为 0。**
     */
    @Select("SELECT COUNT(*) FROM chat_assistant_log "
            + "WHERE kind = 'WRITE_CONFIRM' AND confirmed_by_user = 0")
    int countUnconfirmedWrites();

    /**
     * 按天分组的调用量(看板趋势图用)。
     */
    @Select("SELECT DATE(created_at) AS day, COUNT(*) AS calls "
            + "FROM chat_assistant_log WHERE created_at >= #{since} "
            + "GROUP BY DATE(created_at) ORDER BY day")
    java.util.List<Map<String, Object>> callsByDay(@Param("since") LocalDateTime since);
}

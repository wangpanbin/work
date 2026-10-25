package com.cinema.modules.chat.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对话助手用量看板的统计结果(v0.4.0,IT-11)。
 *
 * <p>对应战略计划 §7.5 的四项指标:调用量 / 失败率 / P95 耗时 / 估算成本,
 * 外加写审计口径的三个数(尝试 / 成功 / 未确认)。
 *
 * <p><b>口径纪律</b>:字段名带 {@code Cents} 的是<strong>美分(USD)</strong>的估算值,
 * 不是财务口径;单价来自配置且需人工维护。
 */
@Data
public class ChatAssistantStatsVO {

    /** 统计窗口(天)。 */
    private Integer days;

    /** 统计起点。 */
    private LocalDateTime since;

    /** 调用总次数(两类事件合计)。 */
    private Long totalCalls;

    /** 失败次数(result=ERROR)。 */
    private Long failedCalls;

    /** 失败率(0~1,保留 4 位小数)。 */
    private Double failureRate;

    /** 涉及会话数。 */
    private Long sessions;

    /** 涉及用户数(匿名为 null,不计入)。 */
    private Long users;

    /** 输入 token 合计。 */
    private Long promptTokens;

    /** 输出 token 合计。 */
    private Long completionTokens;

    /** 估算成本合计(美分 USD)。 */
    private Long costCents;

    /** P95 服务端耗时(毫秒);无样本为 null。 */
    private Integer p95LatencyMs;

    /** 确认下单尝试次数。 */
    private Long writeAttempts;

    /** 确认下单成功次数。 */
    private Long writeSuccess;

    /**
     * <b>红线指标</b>:未经用户显式确认的写操作数。必须恒为 0。
     */
    private Long unconfirmedWrites;

    /** 按天的调用量(趋势图)。 */
    private List<Map<String, Object>> dailyCalls;
}

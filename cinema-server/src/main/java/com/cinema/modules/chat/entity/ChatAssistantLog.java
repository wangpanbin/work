package com.cinema.modules.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话助手「写审计 + 用量」日志(v0.4.0,IT-05 + IT-11)。
 *
 * <p>对应表 {@code chat_assistant_log}(见 {@code sql/08_chat_assistant_log.sql})。
 *
 * <p><b>一张表两个口径</b>:
 * <ul>
 *   <li><b>写审计</b>({@code kind = WRITE_CONFIRM}):每一次「用户点确认 → 锁座」的尝试留一行。
 *       {@code confirmedByUser == 0} 表示「这次写不是用户显式确认触发的」——
 *       助手本身没有任何写工具({@code ChatTools} 全是只读 {@code @Tool}),
 *       写入口只有确认端点一处,所以这个计数**结构上恒为 0**。
 *       本表的价值是把它变成**可查证的 0**:将来有人加了写入口却忘了打标,
 *       红线查询会立刻暴露它。</li>
 *   <li><b>用量</b>({@code kind = CHAT} 与 {@code WRITE_CONFIRM}):调用量、失败率、
 *       P95 耗时与估算成本的数据源。</li>
 * </ul>
 *
 * <p><b>用量未知记 null,不记 0</b> —— 0 会被误读成「这次调用免费」。
 * 同理 {@code costCents} 在单价未配置或用量未知时是 null。
 */
@Data
@TableName("chat_assistant_log")
public class ChatAssistantLog {

    /** 事件类别:一次对话调用。 */
    public static final String KIND_CHAT = "CHAT";

    /** 事件类别:一次确认下单。 */
    public static final String KIND_WRITE_CONFIRM = "WRITE_CONFIRM";

    /** 结果:成功。 */
    public static final String RESULT_OK = "OK";

    /** 结果:座位被他人抢占。 */
    public static final String RESULT_CONFLICT = "CONFLICT";

    /** 结果:提案已过期或已被消费。 */
    public static final String RESULT_EXPIRED = "EXPIRED";

    /** 结果:下单功能被回滚闸门关掉。 */
    public static final String RESULT_DISABLED = "DISABLED";

    /** 结果:调用出错。 */
    public static final String RESULT_ERROR = "ERROR";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话 ID;匿名对话后端会自生成 fallback id,故不会为空串。 */
    private String chatSessionId;

    /** 用户 ID;匿名对话为 null。 */
    private Long userId;

    /** 事件类别,取值 {@link #KIND_CHAT} / {@link #KIND_WRITE_CONFIRM}。 */
    private String kind;

    /**
     * 写操作是否经用户显式确认。
     *
     * <p>{@code null} = 不适用(非写事件);{@code 1} = 用户显式确认;
     * {@code 0} = 未经确认的写操作(**事故**,红线指标)。
     */
    private Integer confirmedByUser;

    /** 下单提案 ID。 */
    private String proposalId;

    /** 生成的订单号。 */
    private String orderNo;

    /** 结果码,取值 {@link #RESULT_OK} 等。 */
    private String result;

    /** 服务端处理耗时(毫秒)。 */
    private Integer latencyMs;

    /** LLM 输入 token;null = 用量未知。 */
    private Integer promptTokens;

    /** LLM 输出 token;null = 用量未知。 */
    private Integer completionTokens;

    /** 估算成本(美分 USD);null = 单价未配置或用量未知。 */
    private Long costCents;

    private LocalDateTime createdAt;
}

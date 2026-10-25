package com.cinema.modules.chat.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * T1 cycle 3 — 行动卡片 VO(spec §6.1 schema)。
 *
 * <p>既有卡型(SEAT_SUGGESTION)只携带展示与跳转所需的信息(sessionId + seatIndexes + 价格 + 描述),
 * <b>不携带任何可直接提交的载荷</b>(ADR-0002 — 不执行写操作)。
 * 前端点击后跳到 {@code /seat/{sessionId}} 并通过 {@code preselect} 预选,
 * 锁座仍由用户在既有流程确认后经 {@code POST /orders/lock} 发生。
 *
 * <p><b>ORDER_PROPOSAL 是 ADR-0004 的修订例外</b>:它多带一个 {@code proposalId},
 * 但那只是「服务端快照的引用」, 不是可提交的座位载荷 —— 确认端点只凭这个 ID
 * 去 Redis 取服务端自己算好的座位与价格, 客户端依然碰不到座位。
 *
 * cycle 3 阶段占位,T2 接入工具后由 ChatTools 填充。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionCardVO {

    /** 卡型: 只读座位建议卡(点一下跳选座页, 不写库)。 */
    public static final String TYPE_SEAT_SUGGESTION = "SEAT_SUGGESTION";

    /** 卡型: 一键下单确认卡(ADR-0004)。带 proposalId, 可在对话内触发一次锁座。 */
    public static final String TYPE_ORDER_PROPOSAL = "ORDER_PROPOSAL";

    /**
     * 卡型: 取消/退票**引导卡**(v0.4.0 IT-06)。
     *
     * <p>它只带「去哪儿做什么」的跳转信息 —— 助手**不执行**取消或退票,
     * 用户点了卡跳到订单页, 自己操作。既有的取消/退票端点与 ADR-0002 都不受影响。
     */
    public static final String TYPE_ORDER_GUIDE = "ORDER_GUIDE";

    /** 引导动作: 去订单页取消待支付单。 */
    public static final String GUIDE_CANCEL = "CANCEL";

    /** 引导动作: 去订单页申请退票。 */
    public static final String GUIDE_REFUND = "REFUND";

    /** SEAT_SUGGESTION 等(预留枚举空间,cycle 3 用 String 占位) */
    private String type;
    /** 雪花 ID 字符串,前端 URL 用 */
    private String sessionId;
    private String movieTitle;
    private String hallName;
    /** yyyy-MM-dd HH:mm:ss 格式 */
    private String startTime;
    private BigDecimal price;
    /** 座位索引列表(整数) */
    private List<Integer> seatIndexes;
    /** 行列表述,展示用,如 "5排4座、5排5座" */
    private String seatDesc;
    private BigDecimal totalAmount;
    /** 卡片按钮文案,如 "去选座确认" */
    private String actionLabel;

    // ============ ADR-0004:一键下单确认卡专用字段(既有卡型一律为 null)============

    /**
     * 下单提案 ID(服务端生成的 UUID)。
     *
     * <p>它是「服务端快照的引用」, 不是可直接提交的载荷 ——
     * 确认端点只凭这个 ID 去 Redis 取服务端自己算好的座位与价格。
     */
    private String proposalId;

    /** 提案过期时刻(epoch millis)。卡片用它渲染倒计时。 */
    private Long expiresAt;

    /** 提案张数(1~4)。 */
    private Integer count;

    /**
     * 确认后将被取消的同场次待支付订单号(ADR-0002 证据 1)。
     * 非空时卡片必须显式提示用户, 让「静默取消」变成「知情取消」。
     */
    private String pendingCancelOrderNo;

    /** 上述待支付单的支付截止时刻(epoch millis)。 */
    private Long pendingCancelExpireAt;

    /** 稳定的卡片标识(装配器生成)。用于前端 v-for key, 避免丢卡后索引位移导致 DOM 复用错乱。 */
    private String cardId;

    // ============ 锁座成功后的「待支付卡」字段(前端本地生成, 不来自模型)============

    // ============ v0.4.0 IT-06: 取消/退票引导卡字段(其它卡型一律为 null)============

    /** 引导动作:CANCEL / REFUND。 */
    private String guideAction;

    /** 引导卡的目标路由(固定 /orders)。 */
    private String guideRoute;

    /** 引导卡的补充说明(展示用,非载荷)。 */
    private String guideHint;

    /** 已创建的订单号。 */
    private String orderNo;

    /** 支付窗口截止时刻(epoch millis)。 */
    private Long orderExpireAt;
}
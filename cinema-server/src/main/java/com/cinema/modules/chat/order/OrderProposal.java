package com.cinema.modules.chat.order;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下单提案快照 —— 对话助手「一键下单」的服务端快照(ADR-0004)。
 *
 * <p><b>提案不占位</b>:这份快照只是一份「已经算好的报价与座位方案」,
 * TTL 期间座位<b>不保证可用</b>。真正占位只发生在用户点「确认下单」后调用
 * {@code OrderLockService#lockSeats} 的那一刻 —— 与 {@code CONTEXT.md} 里
 * 「选座是意图,锁座是事实」「座位关注从不代替用户发起锁座」一致。
 *
 * <p><b>为什么需要它</b>:ADR-0002 反对助手写操作的三条证据里最硬的一条是
 * 「模型重试或幻觉即产生第二把幂等钥匙」。本快照把「座位列表 / 单价 / 总额」
 * 全部钉在<b>服务端</b>生成 —— 模型说的座位只是提示,模型的报价直接丢弃。
 * 确认时用 {@code proposalId} 作幂等键,于是模型的幻觉与重试<b>产生不了第二把钥匙</b>。
 *
 * <p><b>归属绑定</b>:{@code userId} 存在快照里,确认时与当前登录态比对,
 * 防止拿到他人 proposalId 冒用(同类风险见 {@code test/e2e-report-2026-09-29.md} P0-1)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProposal {

    /** 提案 ID(UUID)。确认端点的幂等键来源 —— 不是 userId:sessionId:seatIndexes。 */
    private String proposalId;

    /** 归属用户。确认时必须与登录态一致,否则拒绝。 */
    private Long userId;

    private Long sessionId;

    /** 服务端核验过的座位索引,一定落在 [0, seatCount) 且同行连座。 */
    private List<Integer> seatIndexes;

    /** 人类可读座位描述,如 "6排3座、6排4座"。 */
    private String seatDesc;

    /** 单价 —— 取自 session 表,不是模型给的数字。 */
    private BigDecimal unitPrice;

    /** 总额 = 单价 x 座位数。 */
    private BigDecimal totalAmount;

    private String movieTitle;

    private String hallName;

    /** yyyy-MM-dd HH:mm:ss */
    private String startTime;

    /** 提案过期时刻(epoch millis)。 */
    private Long expiresAt;

    /** 同场次已有的待支付订单号 —— 确认后会被取消(ADR-0002 证据 1,必须让用户知情)。 */
    private String pendingCancelOrderNo;

    /** 该待支付单的支付截止时刻(epoch millis),无则 null。 */
    private Long pendingCancelExpireAt;
}

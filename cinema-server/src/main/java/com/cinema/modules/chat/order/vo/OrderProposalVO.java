package com.cinema.modules.chat.order.vo;

import com.cinema.modules.chat.order.OrderProposal;
import com.cinema.modules.order.vo.LockResultVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

/**
 * 下单提案对外视图(ADR-0004)。
 *
 * <p>既用于「建提案 / 重新找一组」的响应, 也用于「确认下单」成功后的响应 ——
 * 后者会多带 {@code orderNo} 与 {@code orderExpireAt}(支付窗口截止时刻)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProposalVO {

    /** 影院业务时区, 与 ChatAssistantService 一致。 */
    private static final ZoneId CINEMA_ZONE = ZoneId.of("Asia/Shanghai");

    private String proposalId;
    /**
     * 场次 ID —— <b>用 String 不用 Long</b>。
     *
     * <p>雪花 ID 是 19 位十进制数, 远超 JS 的 2^53 安全整数; 序列化成 JSON 数字后
     * 前端会静默丢精度。前端的 {@code ActionCardVO.sessionId} 早就是 String,
     * 这里必须保持一致。
     */
    private String sessionId;
    private List<Integer> seatIndexes;
    private String seatDesc;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private String movieTitle;
    private String hallName;
    private String startTime;
    /** 提案过期时刻(epoch millis) */
    private Long expiresAt;
    /** 确认后将被取消的同场次待支付订单号 */
    private String pendingCancelOrderNo;
    /** 上述待支付单的支付截止时刻(epoch millis) */
    private Long pendingCancelExpireAt;

    /** 已创建的订单号(仅确认成功后有) */
    private String orderNo;
    /** 支付窗口截止时刻(epoch millis, 仅确认成功后有) */
    private Long orderExpireAt;

    public static OrderProposalVO of(OrderProposal p) {
        return OrderProposalVO.builder()
                .proposalId(p.getProposalId())
                .sessionId(String.valueOf(p.getSessionId()))
                .seatIndexes(p.getSeatIndexes())
                .seatDesc(p.getSeatDesc())
                .unitPrice(p.getUnitPrice())
                .totalAmount(p.getTotalAmount())
                .movieTitle(p.getMovieTitle())
                .hallName(p.getHallName())
                .startTime(p.getStartTime())
                .expiresAt(p.getExpiresAt())
                .pendingCancelOrderNo(p.getPendingCancelOrderNo())
                .pendingCancelExpireAt(p.getPendingCancelExpireAt())
                .build();
    }

    /** 确认成功: 提案信息 + 新建订单的支付窗口。 */
    public static OrderProposalVO ofLocked(OrderProposal p, LockResultVO lock) {
        OrderProposalVO vo = of(p);
        vo.setOrderNo(lock.getOrderNo());
        vo.setOrderExpireAt(lock.getExpireAt() == null
                ? null
                : lock.getExpireAt().atZone(CINEMA_ZONE).toInstant().toEpochMilli());
        // 提案已被消费, 不再是「待确认」状态
        vo.setExpiresAt(null);
        return vo;
    }
}

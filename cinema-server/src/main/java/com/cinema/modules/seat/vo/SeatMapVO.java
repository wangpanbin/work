package com.cinema.modules.seat.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 座位图响应: 位图为 Base64(字节内高位在前), 第 i 位对应 seat_index=i
 * 前端状态推导: sold=1→已售; lock=1→(我锁的?高亮:置灰); 其余可选
 */
@Data
@Builder
public class SeatMapVO {

    private Long sessionId;
    private String movieTitle;
    private String hallName;
    private LocalDateTime startTime;
    private BigDecimal price;

    private int rows;
    private int cols;
    private List<Integer> vipRowNos;
    private int seatCount;

    private String lockBitmap;
    private String soldBitmap;

    /** 当前用户在该场次的待支付座位(锁定态但显示为自己的) */
    private List<Integer> myLockedSeats;

    /**
     * 当前用户在该场次的存量待支付单号(没有则 null)。
     *
     * <p>P0-2 修复(2026-10-25): 座位图接口原先只给「我锁的座位」, 不给「我有一笔待支付单」。
     * 前端因此无法在选座页提示「重新选座会取消你现有的待支付单」——
     * 同一后端副作用在聊天那条路径是披露了的(见 OrderProposalVO#pendingCancelOrderNo),
     * 选座这条路径一个字都不说。
     *
     * <p>口径与 {@code PendingOrderQuery#find} 一致: 同一用户 + 同一场次 + PENDING_PAY,
     * 以 DB 为权威源(Redis 的 user:pending 只是写入镜像, 从不被读)。
     */
    private String pendingOrderNo;

    /**
     * 上述待支付单的过期时间(epoch 毫秒, 可空)。
     *
     * <p>时间口径与 {@code OrderProposalService} 的 pendingCancelExpireAt 完全一致:
     * {@code expireAt.atZone(Asia/Shanghai).toInstant().toEpochMilli()}。
     */
    private Long pendingExpireAt;
}

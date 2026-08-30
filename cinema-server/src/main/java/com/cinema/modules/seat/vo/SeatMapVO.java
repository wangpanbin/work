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
}

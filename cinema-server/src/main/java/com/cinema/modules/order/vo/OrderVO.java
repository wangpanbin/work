package com.cinema.modules.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderVO {

    private String orderNo;
    private Long sessionId;
    private Integer status;
    private String statusText;

    private String movieTitle;
    private String hallName;
    private LocalDateTime startTime;

    private List<Integer> seatIndexes;
    /** 如 "5排6座、5排7座" */
    private String seatDesc;

    private BigDecimal totalAmount;
    private Integer seatCount;

    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}

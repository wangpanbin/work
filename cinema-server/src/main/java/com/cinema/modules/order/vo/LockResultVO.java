package com.cinema.modules.order.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class LockResultVO {

    private String orderNo;
    private LocalDateTime expireAt;
    private BigDecimal totalAmount;
    private List<Integer> seatIndexes;
}

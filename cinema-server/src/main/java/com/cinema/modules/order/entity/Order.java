package com.cinema.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单。pending_key 为数据库生成列(CASE WHEN status=0 THEN session_id END),
 * 由 MySQL 维护, 不映射到实体。
 */
@Data
@TableName("`order`")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务单号(雪花) */
    private String orderNo;

    private Long userId;

    private Long sessionId;

    /** 0待支付 1已支付 2已取消 */
    private Integer status;

    private BigDecimal totalAmount;

    private Integer seatCount;

    /** 支付截止时间 */
    private LocalDateTime expireAt;

    private LocalDateTime paidAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

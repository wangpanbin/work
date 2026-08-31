package com.cinema.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款日志 — N1 退票流程记录
 * <p>仅用于审计/对账, 不参与业务状态机
 */
@Data
@TableName("refund_log")
public class RefundLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;
    private Long userId;
    private BigDecimal amount;
    /** 0成功 1失败 */
    private Integer status;
    private String reason;
    private LocalDateTime createdAt;
}

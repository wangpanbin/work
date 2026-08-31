package com.cinema.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 座位索引列表(非持久化, Phase B-⑦ 优化)
     * <p>在 createPendingOrder / closeIfUnpaid 处一次性查 order_item 并回填,
     * closeOrder 优先用此字段, 免去再次 selectList 的 DB IO.
     * <p>{@code exist = false} 告诉 MyBatis-Plus 不参与 updateById 写入.
     */
    @TableField(exist = false)
    private List<Integer> seatIndexCache;
}

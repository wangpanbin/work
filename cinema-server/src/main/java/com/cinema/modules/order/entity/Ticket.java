package com.cinema.modules.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 电子票 — N2 二维码凭证
 * <p>支付成功后生成, 内容 HMAC 签名, 24h 过期
 */
@Data
@TableName("ticket")
public class Ticket {

    @TableId(type = IdType.ASSIGN_ID)
    private String orderNo;

    private Long userId;
    private Long sessionId;
    private String payload;
    private String sig;
    private LocalDateTime expAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime createdAt;
}

package com.cinema.modules.chat.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 「重新找一组」请求体(ADR-0004)。
 *
 * <p>只有场次与张数两个参数 —— <b>座位不由客户端决定</b>。
 * 服务端会重读实时位图重新挑一组连座(决策 D8),客户端没有任何途径指定具体座位。
 */
@Data
public class OrderProposalDTO {

    @NotNull(message = "场次不能为空")
    @Positive(message = "场次不合法")
    private Long sessionId;

    @NotNull(message = "购票张数不能为空")
    @Min(value = 1, message = "至少选择1个座位")
    @Max(value = 4, message = "每次最多选择4个座位")
    private Integer count;
}

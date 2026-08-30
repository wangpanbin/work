package com.cinema.modules.order.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class LockSeatsDTO {

    @NotNull(message = "场次不能为空")
    private Long sessionId;

    @NotEmpty(message = "请至少选择一个座位")
    @Size(max = 4, message = "每次最多选择4个座位")
    private List<Integer> seatIndexes;
}

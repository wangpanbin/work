package com.cinema.modules.session.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SessionDTO {

    @NotNull(message = "影片不能为空")
    private Long movieId;

    @NotNull(message = "影厅不能为空")
    private Long hallId;

    @NotNull(message = "开映时间不能为空")
    @Future(message = "开映时间需在未来")
    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @NotNull(message = "票价不能为空")
    @DecimalMin(value = "0.01", message = "票价需大于0")
    private BigDecimal price;

    /** 0待开售 1在售 2已开场 3已结束 */
    private Integer status;
}
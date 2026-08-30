package com.cinema.modules.hall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class HallDTO {

    @NotNull(message = "影院不能为空")
    private Long cinemaId;

    @NotBlank(message = "影厅名不能为空")
    private String name;

    @NotNull(message = "行数不能为空")
    @Positive(message = "行数需为正数")
    private Integer seatRows;

    @NotNull(message = "列数不能为空")
    @Positive(message = "列数需为正数")
    private Integer seatCols;

    /** VIP 起始行(含); null 表示无 VIP */
    private Integer vipFromRow;
}
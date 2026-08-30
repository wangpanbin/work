package com.cinema.modules.movie.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class MovieDTO {

    @NotBlank(message = "片名不能为空")
    private String title;

    private String poster;

    @NotNull(message = "时长不能为空")
    @Positive(message = "时长需为正数")
    private Integer duration;

    private String description;

    /** 0下架 1热映 */
    private Integer status;
}
package com.cinema.modules.movie.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class MovieDTO {

    @NotBlank(message = "片名不能为空")
    @Size(max = 128, message = "片名最长 128 个字符")
    private String title;

    private String poster;

    @NotNull(message = "时长不能为空")
    @Positive(message = "时长需为正数")
    private Integer duration;

    private String description;

    /** 0下架 1热映 */
    private Integer status;

    /** F1 搜索筛选扩展字段 */
    private String genre;
    private String region;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate releaseDate;
}

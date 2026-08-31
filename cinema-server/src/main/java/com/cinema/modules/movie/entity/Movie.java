package com.cinema.modules.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("movie")
public class Movie {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;

    /** 海报URL, 空则前端用占位图 */
    private String poster;

    /** 时长(分钟) */
    private Integer duration;

    private String description;

    /** 0下架 1热映 */
    private Integer status;

    /** F1 搜索筛选扩展字段 */
    private String genre;
    private String region;
    private LocalDate releaseDate;

    private LocalDateTime createdAt;
}

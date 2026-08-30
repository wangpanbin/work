package com.cinema.modules.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("`session`")
public class Session {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long movieId;

    private Long hallId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private BigDecimal price;

    /** 0待开售 1在售 2已开场 3已结束 */
    private Integer status;
}

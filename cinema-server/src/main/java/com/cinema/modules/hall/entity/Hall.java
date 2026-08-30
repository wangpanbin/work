package com.cinema.modules.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("hall")
public class Hall {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long cinemaId;

    private String name;

    /** 行数 */
    private Integer seatRows;

    /** 列数 */
    private Integer seatCols;

    private Integer seatCount;
}

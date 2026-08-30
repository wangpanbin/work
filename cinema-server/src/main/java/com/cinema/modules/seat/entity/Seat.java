package com.cinema.modules.seat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("seat")
public class Seat {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long hallId;

    /** 座位序号, 对应 Redis Bitmap 位 */
    private Integer seatIndex;

    private Integer rowNo;

    private Integer colNo;

    /** 0普通 1VIP */
    private Integer seatType;

    private Integer x;

    private Integer y;
}

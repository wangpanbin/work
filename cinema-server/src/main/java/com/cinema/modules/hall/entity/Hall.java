package com.cinema.modules.hall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * VIP 起始行(含, 行号从 1 起算); null = 无 VIP 区。
     *
     * <p><b>这是派生值</b> —— VIP 归属的唯一真源是 {@code seat.seat_type = 1},
     * {@code hall} 表里并没有这一列。所以必须标 {@code exist = false}:
     * 否则 MyBatis-Plus 会去 SELECT/UPDATE 一个数据库里不存在的列 → SQL 直接报错。
     *
     * <p>语义是**行号**(与 {@code insertSeats} 里 {@code r >= vipFromRow} 的 r 同一种),
     * 不是座位索引 —— 前端回填后原样提交, 两侧必须同一种。
     */
    @TableField(exist = false)
    private Integer vipFromRow;
}

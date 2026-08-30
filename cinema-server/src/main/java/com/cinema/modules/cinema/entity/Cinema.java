package com.cinema.modules.cinema.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("cinema")
public class Cinema {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String address;
}

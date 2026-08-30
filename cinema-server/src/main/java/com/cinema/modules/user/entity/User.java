package com.cinema.modules.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    /** BCrypt 散列, 永不出库 */
    private String password;

    private String nickname;

    private String phone;

    /** 0普通用户 1管理员 */
    private Integer role;

    private LocalDateTime createdAt;
}

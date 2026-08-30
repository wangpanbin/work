package com.cinema.modules.user.vo;

import com.cinema.modules.user.entity.User;
import lombok.Data;

@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String phone;
    /** 0普通用户 1管理员 */
    private Integer role;

    public static UserVO from(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setRole(user.getRole() == null ? 0 : user.getRole());
        return vo;
    }
}
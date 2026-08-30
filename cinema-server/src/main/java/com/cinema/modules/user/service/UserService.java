package com.cinema.modules.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.context.UserContext;
import com.cinema.common.exception.BizException;
import com.cinema.infra.jwt.JwtUtil;
import com.cinema.modules.user.dto.LoginDTO;
import com.cinema.modules.user.dto.RegisterDTO;
import com.cinema.modules.user.entity.User;
import com.cinema.modules.user.mapper.UserMapper;
import com.cinema.modules.user.vo.LoginVO;
import com.cinema.modules.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public void register(RegisterDTO dto) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BizException("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setPhone(dto.getPhone() == null ? "" : dto.getPhone());
        userMapper.insert(user);
    }

    public LoginVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        String token = jwtUtil.generate(user.getId(), user.getUsername(),
                user.getRole() == null ? 0 : user.getRole());
        return new LoginVO(token, UserVO.from(user));
    }

    public UserVO currentUser() {
        User user = userMapper.selectById(UserContext.userId());
        if (user == null) {
            throw new BizException("登录状态无效,请重新登录");
        }
        return UserVO.from(user);
    }
}

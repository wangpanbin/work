package com.cinema.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.modules.user.entity.User;
import com.cinema.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * dev 环境测试账号初始化: 确保 user1/user2 密码为 123456 (BCrypt)
 * 数据库未就绪时仅告警, 不阻断启动
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        try {
            ensureUser("user1", "测试用户1", "13800000001", 0);
            ensureUser("user2", "测试用户2", "13800000002", 0);
            ensureUser("admin", "系统管理员", "13900000000", 1);
            log.info("测试账号就绪: user1/123456, user2/123456, admin/123456");
        } catch (Exception e) {
            log.warn("测试账号初始化失败(数据库未就绪?), 启动继续: {}", e.getMessage());
        }
    }

    private void ensureUser(String username, String nickname, String phone, int role) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            user = new User();
            user.setUsername(username);
            user.setNickname(nickname);
            user.setPhone(phone);
            user.setRole(role);
            user.setPassword(passwordEncoder.encode("123456"));
            userMapper.insert(user);
        } else {
            user.setRole(role);
            if (!user.getPassword().startsWith("$2")) {
                user.setPassword(passwordEncoder.encode("123456"));
            }
            userMapper.updateById(user);
        }
    }
}

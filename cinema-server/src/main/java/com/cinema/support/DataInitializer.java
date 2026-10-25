package com.cinema.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.modules.user.entity.User;
import com.cinema.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * dev 环境测试账号初始化: 确保 user1/user2 密码为 123456 (BCrypt)
 * 数据库未就绪时仅告警, 不阻断启动
 *
 * <p><b>管理员播种开关</b>(2026-10-24): {@code cinema.seed.admin-enabled}。
 * 原先本类只受 {@code @Profile("dev")} 约束, 而 {@code spring.profiles.active: dev}
 * 又硬编码在基础 {@code application.yml} 里 —— 于是任何未显式覆盖 profile 的部署
 * 都会拿到一个 {@code admin/123456}(role=1)。现在 profile 本身可用环境变量切换,
 * 并且播种管理员单独有开关, 生产部署要显式关掉。
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 是否播种管理员账号。默认 true(dev 本地开发要用),
     * 生产部署设 {@code CINEMA_SEED_ADMIN_ENABLED=false} 或切到非 dev profile。
     */
    @Value("${cinema.seed.admin-enabled:true}")
    private boolean adminEnabled;

    @Override
    public void run(String... args) {
        try {
            ensureUser("user1", "测试用户1", "13800000001", 0);
            ensureUser("user2", "测试用户2", "13800000002", 0);
            if (adminEnabled) {
                ensureUser("admin", "系统管理员", "13900000000", 1);
                log.info("测试账号就绪: user1/123456, user2/123456, admin/123456");
            } else {
                log.info("测试账号就绪: user1/123456, user2/123456(管理员播种已关闭)");
            }
        } catch (Exception e) {
            log.warn("测试账号初始化失败(数据库未就绪?), 启动继续: {}", e.getMessage());
        }
    }

    /**
     * 确保账号存在且密码是 123456。
     *
     * <p><b>不覆盖已有账号的 role</b>(2026-10-24): 原先每次启动都无条件
     * {@code user.setRole(role)} 再 update —— 人工把某个账号降权/提权后,
     * 一次重启就被改回去。role 只在**新建**时写入。
     */
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
            if (!user.getPassword().startsWith("$2")) {
                user.setPassword(passwordEncoder.encode("123456"));
                userMapper.updateById(user);
            }
        }
    }
}

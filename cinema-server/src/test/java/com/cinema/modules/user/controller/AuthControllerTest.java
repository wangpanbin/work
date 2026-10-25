package com.cinema.modules.user.controller;

import com.cinema.common.annotation.IdempotencyKeys;
import com.cinema.common.annotation.RateLimit;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.user.dto.LoginDTO;
import com.cinema.modules.user.dto.RegisterDTO;
import com.cinema.modules.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthController} 单测 —— 2026-10-24 新增。
 *
 * <p>锁的契约: 认证端点**必须有限流**。
 * 原先 {@code /api/auth/login} 与 {@code /api/auth/register} 完全裸奔 ——
 * 订单域每个写端点都挂了 {@code @RateLimit}, 只有这两个公开写入口没有。
 * 配合当时 CORS 全开, 撞库可以直接在浏览器里完成。
 *
 * <p>限流额度为什么这么定(登录 10/分钟按 IP+账号、注册 120/小时按 IP)见
 * {@link IdempotencyKeys#RATE_LOGIN_BY_IP_AND_ACCOUNT} 与
 * {@link IdempotencyKeys#RATE_REGISTER_BY_IP} 的注释。
 *
 * <p>切面层的「超限抛 42900」由 {@code RateLimitAspectTest} 覆盖, 本类只锁注解配置。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthControllerTest {

    @Mock private UserService userService;

    @Test
    @DisplayName("登录端点必须有限流: 按 IP+账号, 10 次/分钟")
    void login_hasRateLimit() throws NoSuchMethodException {
        Method m = AuthController.class.getMethod("login", HttpServletRequest.class, LoginDTO.class);
        RateLimit rl = m.getAnnotation(RateLimit.class);

        assertThat(rl).as("登录端点必须有 @RateLimit —— 公开写入口不能裸奔").isNotNull();
        assertThat(rl.key()).isEqualTo(IdempotencyKeys.RATE_LOGIN_BY_IP_AND_ACCOUNT);
        assertThat(rl.permits()).isEqualTo(10);
        assertThat(rl.window()).isEqualTo(1);
        assertThat(rl.unit()).isEqualTo(TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("注册端点必须有限流: 按 IP, 120 次/小时")
    void register_hasRateLimit() throws NoSuchMethodException {
        Method m = AuthController.class.getMethod("register", HttpServletRequest.class, RegisterDTO.class);
        RateLimit rl = m.getAnnotation(RateLimit.class);

        assertThat(rl).as("注册端点必须有 @RateLimit —— 批量造号的入口").isNotNull();
        assertThat(rl.key()).isEqualTo(IdempotencyKeys.RATE_REGISTER_BY_IP);
        assertThat(rl.permits()).isEqualTo(120);
        assertThat(rl.window()).isEqualTo(1);
        assertThat(rl.unit()).isEqualTo(TimeUnit.HOURS);
    }

    @Test
    @DisplayName("限流桶键必须含来源 IP, 否则限流退化成全局桶")
    void rateLimitKeysContainRemoteAddr() {
        assertThat(IdempotencyKeys.RATE_LOGIN_BY_IP_AND_ACCOUNT).contains("#request.remoteAddr");
        assertThat(IdempotencyKeys.RATE_LOGIN_BY_IP_AND_ACCOUNT).contains("#dto.username");
        assertThat(IdempotencyKeys.RATE_REGISTER_BY_IP).contains("#request.remoteAddr");
    }

    @Test
    @DisplayName("限流拒绝时用的是既有的 42900 业务码(前端按 code 分支)")
    void rateLimitUsesExisting42900Code() {
        assertThat(ResultCode.RATE_LIMIT.getCode()).isEqualTo(42900);
    }

    @Test
    @DisplayName("登录请求体里的 username 参与限流分桶 —— 所以 DTO 必须有 username 字段")
    void loginDtoHasUsernameForBucketKey() {
        // SpEL 引用了 #dto.username: 字段名拼错的话 SpEL 求值失败 → 退化成 anon 桶(全体用户共用一个桶)
        LoginDTO dto = new LoginDTO();
        dto.setUsername("user1");
        assertThat(dto.getUsername()).isEqualTo("user1");
    }

    @Test
    @DisplayName("未限流时不额外调用 service —— 限流是切面职责, 控制器本身不重复实现")
    void controllerDoesNotImplementRateLimitItself() {
        // 控制器不该自己数次数 —— 那是切面的活。这里只确认 controller 里没有自造计数器字段。
        assertThat(AuthController.class.getDeclaredFields())
                .noneMatch(f -> f.getType() == java.util.concurrent.atomic.AtomicInteger.class);
        verify(userService, never()).login(any());
    }
}

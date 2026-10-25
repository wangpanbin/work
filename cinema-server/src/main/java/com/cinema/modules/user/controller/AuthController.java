package com.cinema.modules.user.controller;

import com.cinema.common.annotation.IdempotencyKeys;
import com.cinema.common.annotation.RateLimit;
import com.cinema.common.result.R;
import com.cinema.modules.user.dto.LoginDTO;
import com.cinema.modules.user.dto.RegisterDTO;
import com.cinema.modules.user.service.UserService;
import com.cinema.modules.user.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 认证入口 —— 注册 / 登录。
 *
 * <p><b>限流</b>(2026-10-24): 这两个端点原先**完全没有限流**, 是全站唯一裸奔的公开写入口
 * —— 配合当时 CORS 全开(任意站点都能读响应), 撞库可以直接在浏览器里完成。
 * 订单域每个写端点都挂了 {@code @RateLimit}, 认证端点漏了。
 *
 * <p>桶键怎么选见 {@link IdempotencyKeys#RATE_LOGIN_BY_IP_AND_ACCOUNT} 与
 * {@link IdempotencyKeys#RATE_REGISTER_BY_IP} 的注释 —— 简单说:
 * 登录按「IP + 账号」分桶(按住撞库, 不误伤批量建号), 注册按「IP」分桶(每小时限量)。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    @RateLimit(key = IdempotencyKeys.RATE_REGISTER_BY_IP,
            permits = 120, window = 1, unit = TimeUnit.HOURS,
            message = "注册过于频繁,请稍后再试")
    public R<Void> register(HttpServletRequest request, @Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return R.ok();
    }

    @PostMapping("/login")
    @RateLimit(key = IdempotencyKeys.RATE_LOGIN_BY_IP_AND_ACCOUNT,
            permits = 10, window = 1, unit = TimeUnit.MINUTES,
            message = "登录尝试过于频繁,请稍后再试")
    public R<LoginVO> login(HttpServletRequest request, @Valid @RequestBody LoginDTO dto) {
        return R.ok(userService.login(dto));
    }
}

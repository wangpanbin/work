package com.cinema.config;

import com.cinema.interceptor.AdminInterceptor;
import com.cinema.interceptor.AuthRequiredInterceptor;
import com.cinema.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AuthRequiredInterceptor authRequiredInterceptor;
    private final AdminInterceptor adminInterceptor;

    /**
     * 允许的跨域来源白名单(2026-10-24)。
     *
     * <p>原先是 {@code allowedOriginPatterns("*")} —— 全开。任意站点的网页都能
     * 带 Cookie / Authorization 读 API 响应, 配合登录端点无限流, 撞库可以在浏览器里直接完成。
     *
     * <p>现在走配置 {@code cinema.cors.allowed-origins}(逗号分隔, 环境变量
     * {@code CORS_ALLOWED_ORIGINS} 可覆盖)。本地默认值保留 Vite dev(5173)与 preview(4173)。
     */
    @Value("${cinema.cors.allowed-origins:http://localhost:5173,http://localhost:4173}")
    private List<String> allowedOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // JWT 可选拦截: 解析 token 注入 UserContext; 无 token 也放行 (后续 AuthRequired/Admin 再校验)
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/**",
                        "/api/movies",
                        "/api/movies/**",
                        "/error");
        // 强制登录: 订单、我的资料
        // ADR-0004: /api/chat/orders/** 也必须强制登录 —— 助手的下单端点会真的锁座,
        // 不能让匿名用户带着别人的 proposalId 调用。(/api/chat/message 仍公开, 现状不变)
        registry.addInterceptor(authRequiredInterceptor)
                .addPathPatterns("/api/orders/**", "/api/users/me", "/api/chat/orders/**");
        // 管理端鉴权: 先 JWT 注入 UserContext, 再 Admin 校验角色
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? new String[0]
                : allowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (origins.length == 0) {
            // 白名单为空 → 不注册任何跨域来源(全默认拒绝), 而不是回退成 "*"
            log.warn("[CORS] cinema.cors.allowed-origins 为空, 本次不开放任何跨域来源");
            return;
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("*")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

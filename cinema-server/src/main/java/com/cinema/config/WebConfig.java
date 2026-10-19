package com.cinema.config;

import com.cinema.interceptor.AdminInterceptor;
import com.cinema.interceptor.AuthRequiredInterceptor;
import com.cinema.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AuthRequiredInterceptor authRequiredInterceptor;
    private final AdminInterceptor adminInterceptor;

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
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

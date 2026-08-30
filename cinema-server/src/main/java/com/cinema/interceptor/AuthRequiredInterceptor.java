package com.cinema.interceptor;

import com.cinema.common.context.UserContext;
import com.cinema.common.result.R;
import com.cinema.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 强制鉴权: 必须在 JwtInterceptor 之后执行,要求 UserContext 中已有 userId。
 * 适用于: 订单 / 我的资料 等必须登录的接口。
 */
@Component
@RequiredArgsConstructor
public class AuthRequiredInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserContext.userId() == null) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(R.fail(ResultCode.UNAUTHORIZED)));
            return false;
        }
        return true;
    }
}

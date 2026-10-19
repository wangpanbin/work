package com.cinema.interceptor;

import com.cinema.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 强制鉴权:必须在 JwtInterceptor 之后执行,要求 UserContext 中已有 userId。
 * 适用于:订单 / 我的资料 等必须登录的接口。
 *
 * <p>判定口径在 {@link AuthPolicy},响应写法在 {@link ApiResponseWriter}。
 */
@Component
@RequiredArgsConstructor
public class AuthRequiredInterceptor implements HandlerInterceptor {

    private final AuthPolicy authPolicy;
    private final ApiResponseWriter responseWriter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ResultCode rejection = authPolicy.rejectionForLogin();
        if (rejection != null) {
            responseWriter.writeFail(response, rejection);
            return false;
        }
        return true;
    }
}

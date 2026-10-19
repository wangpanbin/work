package com.cinema.interceptor;

import com.cinema.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端守卫:仅 role=1 放行。
 *
 * <p>判定口径在 {@link AuthPolicy},响应写法在 {@link ApiResponseWriter}。
 */
@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final AuthPolicy authPolicy;
    private final ApiResponseWriter responseWriter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        ResultCode rejection = authPolicy.rejectionForAdmin();
        if (rejection != null) {
            responseWriter.writeFail(response, rejection);
            return false;
        }
        return true;
    }
}

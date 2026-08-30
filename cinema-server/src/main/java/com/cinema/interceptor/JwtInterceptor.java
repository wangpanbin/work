package com.cinema.interceptor;

import com.cinema.common.context.UserContext;
import com.cinema.common.result.R;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.jwt.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 登录态拦截: 解析 Authorization: Bearer xxx → UserContext
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(auth.substring(7));
                Integer role = claims.get("role", Integer.class);
                UserContext.set(Long.valueOf(claims.getSubject()),
                        (String) claims.get("username"), role == null ? 0 : role);
                return true;
            } catch (JwtException | IllegalArgumentException e) {
                return reject(response, ResultCode.TOKEN_EXPIRED);
            }
        }
        // 可选鉴权: 无 token 也放行,后续 AuthRequiredInterceptor / AdminInterceptor 再做强制校验
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response, ResultCode rc) throws Exception {
        // 统一 HTTP 200 + 业务码, 前端按 code 处理
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(rc)));
        return false;
    }
}

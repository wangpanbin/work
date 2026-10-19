package com.cinema.interceptor;

import com.cinema.common.result.R;
import com.cinema.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 业务失败响应写出 —— 三个拦截器共用同一份写法。
 *
 * <p><b>统一 HTTP 200 + 业务码</b>: 前端 axios 拦截器按 {@code body.code} 分流,
 * 40101 / 40102 / 40301 都有各自的前端处理分支。若把状态码改成 HTTP 401/403,
 * 前端会当成网络错误, 拿不到这些码, 于是「请先登录」的提示就丢了。
 *
 * <p><b>写出的内容逐字不变</b>: {@code {code, msg, data}} 结构、UTF-8 编码、
 * {@code Content-Type: application/json} 都与原先三处逐字副本一致。
 */
@Component
@RequiredArgsConstructor
public class ApiResponseWriter {

    private final ObjectMapper objectMapper;

    /**
     * 写一个业务失败响应。
     *
     * @param response HTTP 响应
     * @param rc       业务码
     */
    public void writeFail(HttpServletResponse response, ResultCode rc) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(rc)));
    }
}

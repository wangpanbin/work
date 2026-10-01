package com.cinema.common.exception;

import com.cinema.common.result.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E 2026-10-01 回归锁 —— 畸形请求体被吞成 50000「系统繁忙」。
 *
 * <p>缺陷:管理端建场次传空格分隔的日期(2026-10-09 19:00:00)时,
 * Jackson 反序列化失败抛 {@link HttpMessageNotReadableException},
 * 原先没有专门 handler,落到 {@code handleOther} 返回 50000,
 * 把「客户端格式写错」伪装成「服务端故障」。
 */
class GlobalExceptionHandlerNotReadableTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("请求体反序列化失败 → 40001 参数错误, 而不是 50000 系统繁忙")
    void notReadable_returns40001Not50000() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException(
                "JSON parse error: Text '2026-10-09 19:00:00' could not be parsed at index 10");

        R<Void> r = handler.handleNotReadable(e);

        assertThat(r.getCode()).isEqualTo(40001);
        assertThat(r.getCode()).isNotEqualTo(50000);
        assertThat(r.getMsg()).contains("ISO");
    }
}

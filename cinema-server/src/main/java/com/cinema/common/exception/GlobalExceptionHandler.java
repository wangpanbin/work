package com.cinema.common.exception;

import com.cinema.common.result.R;
import com.cinema.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Object> handleBiz(BizException e) {
        if (e.getData() != null) {
            return R.failWith(e.getCode(), e.getMessage(), e.getData());
        }
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage())
                .findFirst()
                .orElse(ResultCode.BAD_REQUEST.getMsg());
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 请求体反序列化失败(字段类型/日期格式不合法)→ 40001 参数错误。
     *
     * <p>E2E 2026-10-01:管理端建场次传 {@code startTime:"2026-10-09 19:00:00"}
     * (空格分隔)时,Jackson 抛 {@code InvalidFormatException},此前落到
     * {@link #handleOther} 被吞成 50000「系统繁忙,请稍后重试」——
     * 对只该由人手敲日期框的管理端页面,这条提示会把「我格式写错了」误导成「服务端坏了」。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体格式错误: {}", e.getMessage());
        return R.fail(ResultCode.BAD_REQUEST.getCode(), "请求参数格式错误(日期时间需用 ISO 格式,如 2026-10-09T19:00:00)");
    }

    /**
     * 404: 路径无对应 controller (Spring 6 默认走静态资源处理器抛 NoResourceFoundException).
     * 之前被 handleOther 当 50000 系统异常吞掉, 会掩盖前端路径错配 / 客户端误调.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return R.fail(ResultCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return R.fail(ResultCode.SYSTEM_ERROR);
    }
}

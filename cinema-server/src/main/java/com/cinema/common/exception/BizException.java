package com.cinema.common.exception;

import com.cinema.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常: code 进响应体, data 用于携带附加信息(如锁座冲突座位列表)
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;
    private final transient Object data;

    public BizException(String message) {
        this(ResultCode.BUSINESS_ERROR.getCode(), message, null);
    }

    public BizException(ResultCode rc, String message) {
        this(rc.getCode(), message, null);
    }

    public BizException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
}

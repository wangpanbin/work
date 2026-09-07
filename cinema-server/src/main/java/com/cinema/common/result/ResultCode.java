package com.cinema.common.result;

import lombok.Getter;

/**
 * 业务状态码: 0 成功; 4xxxx 客户端/业务错误; 5xxxx 服务端错误
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "ok"),
    BAD_REQUEST(40001, "参数错误"),
    BUSINESS_ERROR(40002, "业务处理失败"),
    NOT_FOUND(40400, "资源不存在"),
    IDEMPOTENT_CONFLICT(40900, "请勿重复提交"),
    RATE_LIMIT(42900, "请求过于频繁,请稍后重试"),
    UNAUTHORIZED(40101, "未登录"),
    TOKEN_EXPIRED(40102, "登录已过期"),
    FORBIDDEN(40301, "无权限"),
    SYSTEM_ERROR(50000, "系统繁忙,请稍后重试");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}

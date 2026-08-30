package com.cinema.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体: {code, msg, data}
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String msg;
    private T data;

    public static R<Void> ok() {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    public static <T> R<T> ok(T data) {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    public static <T> R<T> fail(ResultCode rc) {
        return build(rc.getCode(), rc.getMsg(), null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return build(code, msg, null);
    }

    public static R<Object> failWith(int code, String msg, Object data) {
        return build(code, msg, data);
    }

    private static <T> R<T> build(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}

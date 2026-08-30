package com.cinema.modules.order.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStatus {

    PENDING_PAY(0, "待支付"),
    PAID(1, "已支付"),
    CANCELLED(2, "已取消");

    private final int code;
    private final String text;

    public static String textOf(int code) {
        for (OrderStatus s : values()) {
            if (s.code == code) {
                return s.text;
            }
        }
        return "未知";
    }
}

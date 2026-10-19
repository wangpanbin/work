package com.cinema.infra.redis;

import java.util.List;

/**
 * 座位写入的意图类型 —— 把「要动哪些位」编码进**类型**, 而不是靠方法名或注释区分。
 *
 * <p>背景: 释放(只清 lock 位)与退票(同清 sold + lock)两个 Lua 脚本的参数签名
 * 一模一样, 只靠方法名区分。E2E 2026-09-29 的 P1-1 就是这么来的 ——
 * 退票误走释放路径 → 座位留在 sold 里 → 刷新后永久灰着不可再售。
 *
 * <p>用两个不同的类型承载, 编译器就能挡住误用: 传错类型根本编译不过。
 *
 * <p>⚠️ 三个座位 Lua 脚本的语义**各自独立**, 不可合并、不可互相替代:
 * <ul>
 *   <li>{@code lock_seat.lua} —— 查 sold+lock 冲突, 再原子置 lock</li>
 *   <li>{@code confirm_seat.lua} —— 置 sold 并清 lock</li>
 *   <li>{@code release_seat.lua} —— 只清 lock 位(已售座位一个位都不动)</li>
 *   <li>{@code refund_seat.lua} —— 同清 sold + lock</li>
 * </ul>
 */
public final class SeatWrite {

    private SeatWrite() {
    }

    /**
     * 待支付订单的座位 —— 取消 / 超时关单。
     *
     * <p>对应 {@code release_seat.lua}: **只清 lock 位**, 已售座位不受影响。
     */
    public record Unpaid(Long sessionId, List<Integer> seats) {
        public Unpaid {
            seats = seats == null ? List.of() : List.copyOf(seats);
        }
    }

    /**
     * 已支付订单的座位 —— 退票。
     *
     * <p>对应 {@code refund_seat.lua}: **同清 sold + lock**, 让座位真正回到可选池。
     */
    public record Paid(Long sessionId, List<Integer> seats) {
        public Paid {
            seats = seats == null ? List.of() : List.copyOf(seats);
        }
    }
}

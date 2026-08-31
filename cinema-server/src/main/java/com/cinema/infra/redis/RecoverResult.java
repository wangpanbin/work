package com.cinema.infra.redis;

/**
 * recover_seat_bitmap.lua 返回结果
 */
public record RecoverResult(int lock, int sold) {
}

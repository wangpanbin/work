package com.cinema.infra.redis;

import java.util.List;

/**
 * lock_seat.lua 返回结果
 */
public record LuaLockResult(boolean ok, List<Integer> conflict) {
}

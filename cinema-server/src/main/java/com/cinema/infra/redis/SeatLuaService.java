package com.cinema.infra.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 座位位图 Lua 脚本封装: 脚本经 DefaultRedisScript 预加载(首次执行后缓存于 Redis, 后续只传 SHA)
 */
@Service
@RequiredArgsConstructor
public class SeatLuaService {

    private static final DefaultRedisScript<String> LOCK_SCRIPT = script("lua/lock_seat.lua");
    private static final DefaultRedisScript<String> CONFIRM_SCRIPT = script("lua/confirm_seat.lua");
    private static final DefaultRedisScript<String> RELEASE_SCRIPT = script("lua/release_seat.lua");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 原子锁座: 冲突返回 conflict 列表, 成功返回空列表 */
    public LuaLockResult lockSeats(String lockKey, String soldKey, List<Integer> seats) {
        String json = redisTemplate.execute(LOCK_SCRIPT, List.of(lockKey, soldKey), args(seats));
        return parse(json);
    }

    /** 支付确认: 返回实际置为 sold 的座位 */
    public List<Integer> confirmSeats(String lockKey, String soldKey, List<Integer> seats) {
        String json = redisTemplate.execute(CONFIRM_SCRIPT, List.of(lockKey, soldKey), args(seats));
        return parseList(json);
    }

    /** 释放: 返回实际释放的座位(已售座位不会被释放) */
    public List<Integer> releaseSeats(String lockKey, String soldKey, List<Integer> seats) {
        String json = redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey, soldKey), args(seats));
        return parseList(json);
    }

    private static DefaultRedisScript<String> script(String location) {
        DefaultRedisScript<String> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(location));
        s.setResultType(String.class);
        return s;
    }

    private static Object[] args(List<Integer> seats) {
        return seats.stream().map(String::valueOf).toArray();
    }

    private LuaLockResult parse(String json) {
        try {
            return objectMapper.readValue(json, LuaLockResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Lua 锁座结果解析失败: " + json, e);
        }
    }

    private List<Integer> parseList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Lua 座位结果解析失败: " + json, e);
        }
    }
}

package com.cinema.infra.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 座位位图 Lua 脚本封装
 * <p>Phase B-④ 优化: 启动期通过 SCRIPT LOAD 把 3 个脚本预热到 Redis,
 * 后续业务调用由 Spring Data Redis 走 EVALSHA(若 Redis 重启脚本丢失,
 * Spring Data Redis 会自动 fallback EVAL, 不影响功能).
 * <p>脚本经 DefaultRedisScript 预加载(首次执行后缓存于 Redis, 后续只传 SHA)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatLuaService {

    private static final DefaultRedisScript<String> LOCK_SCRIPT = script("lua/lock_seat.lua");
    private static final DefaultRedisScript<String> CONFIRM_SCRIPT = script("lua/confirm_seat.lua");
    private static final DefaultRedisScript<String> RELEASE_SCRIPT = script("lua/release_seat.lua");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void preloadScripts() {
        // 预热脚本到 Redis, 把 SHA 打到日志方便 MONITOR 验证
        preload(LOCK_SCRIPT, "lock_seat");
        preload(CONFIRM_SCRIPT, "confirm_seat");
        preload(RELEASE_SCRIPT, "release_seat");
    }

    private void preload(DefaultRedisScript<String> script, String name) {
        try {
            // 强制 lazy 计算 SHA, 与业务调用走相同的 SHA 计算路径
            String sha = script.getSha1();
            if (sha == null) {
                // 触发 ClassPathResource 读取 + SHA 计算
                script.getScriptAsString();
                sha = script.getSha1();
            }
            // 把脚本内容显式 SCRIPT LOAD 一次, Redis 端缓存, 后续业务走 EVALSHA
            String body = script.getScriptAsString();
            String loadedSha = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) conn ->
                    conn.scriptingCommands().scriptLoad(body.getBytes(StandardCharsets.UTF_8)));
            log.info("[SeatLua] 预热脚本 {} → Redis SHA={} 本地 SHA={} bytes={}",
                    name, loadedSha, sha, body.length());
        } catch (Exception e) {
            // 预热失败不影响启动, 首次业务调用 Spring Data Redis 会自动 fallback EVAL
            log.warn("[SeatLua] 预热脚本 {} 失败 err={}", name, e.toString());
        }
    }

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

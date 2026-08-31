package com.cinema.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Redis 滑动窗口限流 — 单方法原子 (ZREMRANGEBYSCORE + ZCARD + ZADD + PEXPIRE).
 * <p>E4 实现: 与 Spring Boot 自动配置共存, 启动期预热脚本.
 */
@Component
@RequiredArgsConstructor
public class RedisSlidingWindow {

    private static final DefaultRedisScript<Long> SCRIPT = buildScript();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static DefaultRedisScript<Long> buildScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/rate_limit_sliding.lua"));
        s.setResultType(Long.class);
        return s;
    }

    @PostConstruct
    public void preload() {
        try {
            String body = SCRIPT.getScriptAsString();
            String sha = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) conn ->
                    conn.scriptingCommands().scriptLoad(body.getBytes(StandardCharsets.UTF_8)));
            // 预热即丢, 不引用避免 lint
            assert sha != null;
        } catch (Exception ignored) {
            // 预热失败无影响, 业务走 EVAL fallback
        }
    }

    /**
     * @return true 允许; false 拒绝
     */
    public boolean tryAcquire(String bucketKey, int permits, long windowMillis) {
        Long result = redisTemplate.execute(
                SCRIPT,
                List.of(bucketKey),
                String.valueOf(permits),
                String.valueOf(windowMillis),
                String.valueOf(System.currentTimeMillis()),
                UUID.randomUUID().toString());
        return result != null && result == 1L;
    }
}

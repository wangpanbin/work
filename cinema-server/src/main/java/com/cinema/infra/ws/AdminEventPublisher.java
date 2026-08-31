package com.cinema.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * D1 管理端事件发布器 — 把锁座/支付/取消/退款事件发到 Redis Pub/Sub
 * <p>供 /ws/admin 大屏订阅
 */
@Component
@RequiredArgsConstructor
public class AdminEventPublisher {

    public static final String CHANNEL = "admin:event";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(String type, Map<String, Object> data) {
        try {
            Map<String, Object> envelope = Map.of(
                    "type", type,
                    "data", data,
                    "ts", LocalDateTime.now().toString());
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (Exception e) {
            // 大屏事件丢就丢, 不抛错
        }
    }
}

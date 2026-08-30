package com.cinema.infra.ws;

import com.cinema.infra.redis.RedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 座位变更事件发布: 经 Redis Pub/Sub 广播(多实例就绪), 由 SeatRedisSubscriber 推给本机 WS 连接
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publishLocked(Long sessionId, List<Integer> seats) {
        publish("LOCKED", sessionId, seats);
    }

    public void publishSold(Long sessionId, List<Integer> seats) {
        publish("SOLD", sessionId, seats);
    }

    public void publishReleased(Long sessionId, List<Integer> seats) {
        publish("RELEASED", sessionId, seats);
    }

    private void publish(String type, Long sessionId, List<Integer> seats) {
        try {
            String payload = objectMapper.writeValueAsString(
                    Map.of("type", type, "sessionId", sessionId, "seats", seats));
            redisTemplate.convertAndSend(RedisKeys.SEAT_EVENT_CHANNEL, payload);
        } catch (Exception e) {
            log.error("[WS] 座位事件发布失败 type={} session={} seats={}", type, sessionId, seats, e);
        }
    }
}

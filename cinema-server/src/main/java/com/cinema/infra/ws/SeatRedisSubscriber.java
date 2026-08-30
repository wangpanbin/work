package com.cinema.infra.ws;

import com.cinema.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 订阅者: seat:event 频道 → 本机 WS 推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatRedisSubscriber implements MessageListener {

    private final SeatWsHandler seatWsHandler;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("[WS-Redis] 收到订阅消息 channel={} payload={}", new String(message.getChannel(), StandardCharsets.UTF_8), payload);
        seatWsHandler.broadcast(payload);
    }
}

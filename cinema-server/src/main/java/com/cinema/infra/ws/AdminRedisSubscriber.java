package com.cinema.infra.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D1 大屏 WebSocket 处理器 — 订阅 admin:event 频道
 * <p>把 Redis Pub/Sub 事件转发给本机所有 /ws/admin 连接
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminRedisSubscriber implements MessageListener {

    private final RedisMessageListenerContainer container;
    private final ObjectMapper objectMapper;

    /** 本机 ws 连接池 */
    private final Set<org.springframework.web.socket.WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void subscribe() {
        container.addMessageListener(this, new ChannelTopic(AdminEventPublisher.CHANNEL));
        log.info("[AdminWs] 订阅 Redis 频道 {}", AdminEventPublisher.CHANNEL);
    }

    public void register(org.springframework.web.socket.WebSocketSession session) {
        sessions.add(session);
        log.info("[AdminWs] 大屏连接: {} 当前连接数={}", session.getId(), sessions.size());
    }

    public void unregister(org.springframework.web.socket.WebSocketSession session) {
        sessions.remove(session);
        log.info("[AdminWs] 大屏断开: {} 当前连接数={}", session.getId(), sessions.size());
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        sessions.forEach(s -> {
            try {
                if (s.isOpen()) s.sendMessage(new org.springframework.web.socket.TextMessage(body));
            } catch (Exception e) {
                log.warn("[AdminWs] 发送失败 sid={} err={}", s.getId(), e.toString());
            }
        });
    }
}

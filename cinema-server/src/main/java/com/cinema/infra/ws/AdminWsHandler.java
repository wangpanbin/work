package com.cinema.infra.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * D1 /ws/admin WebSocket 处理器 — 注册/注销连接到 AdminRedisSubscriber
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminWsHandler extends TextWebSocketHandler {

    private final AdminRedisSubscriber subscriber;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        subscriber.register(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriber.unregister(session);
    }
}

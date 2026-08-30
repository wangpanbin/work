package com.cinema.infra.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 座位图 WebSocket: ws://host/ws/seat/{sessionId}
 * 连接管理(本机) + 收到 Pub/Sub 事件后按场次路由推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatWsHandler extends TextWebSocketHandler {

    /** sessionId -> 本机连接集合 */
    private final Map<Long, Set<WebSocketSession>> roomMap = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long sessionId = extractSessionId(session);
        if (sessionId == null) {
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        roomMap.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("[WS] 连接加入场次 {}, 当前 {} 个连接, total rooms={}", sessionId, roomMap.get(sessionId).size(), roomMap.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if ("PING".equals(message.getPayload())) {
            try {
                session.sendMessage(new TextMessage("PONG"));
            } catch (IOException ignored) {
                // 发送失败等待连接自然关闭
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long sessionId = extractSessionId(session);
        if (sessionId == null) {
            return;
        }
        Set<WebSocketSession> rooms = roomMap.get(sessionId);
        if (rooms != null) {
            rooms.remove(session);
            if (rooms.isEmpty()) {
                roomMap.remove(sessionId, rooms);
            }
        }
        log.info("[WS] 连接关闭场次 {}, status={}", sessionId, status);
    }

    /** Pub/Sub 订阅回调: 按事件中的 sessionId 路由到本机连接 */
    public void broadcast(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            long sessionId = node.path("sessionId").asLong(0);
            log.info("[WS] broadcast 收到事件 sessionId={} payload={}", sessionId, payload);
            if (sessionId <= 0) {
                return;
            }
            Set<WebSocketSession> sessions = roomMap.get(sessionId);
            log.info("[WS] broadcast 查找 roomMap[{}] -> {} 个连接 (总 rooms={})", sessionId, sessions == null ? 0 : sessions.size(), roomMap.size());
            if (sessions == null || sessions.isEmpty()) {
                return;
            }
            TextMessage msg = new TextMessage(payload);
            for (WebSocketSession s : sessions) {
                try {
                    synchronized (s) {
                        if (s.isOpen()) {
                            s.sendMessage(msg);
                            log.info("[WS] broadcast 已发送 sessionId={} -> sessionId={}", sessionId, s.getId());
                        } else {
                            log.info("[WS] broadcast 跳过关闭的连接 sessionId={} sid={}", sessionId, s.getId());
                        }
                    }
                } catch (IOException e) {
                    log.warn("[WS] broadcast 发送失败,移除连接", e);
                    sessions.remove(s);
                }
            }
        } catch (Exception e) {
            log.warn("[WS] 事件推送失败 payload={}", payload, e);
        }
    }

    private Long extractSessionId(WebSocketSession session) {
        try {
            String path = session.getUri().getPath(); // /ws/seat/123
            return Long.valueOf(path.substring(path.lastIndexOf('/') + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
        }
    }
}

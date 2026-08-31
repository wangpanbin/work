package com.cinema.infra.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 座位图 WebSocket: ws://host/ws/seat/{sessionId}
 * 连接管理(本机) + 收到 Pub/Sub 事件后按场次路由推送
 * <p>Phase C-⑧ 优化:
 * <ul>
 *   <li>snapshot 避免并发修改 ConcurrentHashMap.newKeySet</li>
 *   <li>parallelStream 并行 sendMessage, 不再串行</li>
 *   <li>ObjectReader 复用, 不再每次 readTree 都新建</li>
 *   <li>失败连接统一收尾移除, 避免 ConcurrentModificationException</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeatWsHandler extends TextWebSocketHandler {

    /** sessionId -> 本机连接集合 */
    private final Map<Long, Set<WebSocketSession>> roomMap = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private volatile ObjectReader treeReader;       // 复用

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

    /**
     * Pub/Sub 订阅回调: 按事件中的 sessionId 路由到本机连接
     * <p>优化: snapshot + 并行 sendMessage + 复用 ObjectReader
     */
    public void broadcast(String payload) {
        try {
            if (treeReader == null) {
                treeReader = objectMapper.reader();
            }
            JsonNode node = treeReader.readTree(payload);
            long sessionId = node.path("sessionId").asLong(0);
            if (sessionId <= 0) {
                return;
            }
            Set<WebSocketSession> sessions = roomMap.get(sessionId);
            if (sessions == null || sessions.isEmpty()) {
                return;
            }
            // snapshot 避免并发修改
            List<WebSocketSession> snapshot = new ArrayList<>(sessions);
            TextMessage msg = new TextMessage(payload);
            // 并行分发, 每个 session 独立 sendMessage
            // 失败 session 收集到 toRemove, 串行收尾
            List<WebSocketSession> failed = new ArrayList<>();
            snapshot.parallelStream().forEach(s -> {
                if (!s.isOpen()) {
                    failed.add(s);
                    return;
                }
                try {
                    synchronized (s) {  // WebSocketSession 非线程安全
                        if (s.isOpen()) {
                            s.sendMessage(msg);
                        }
                    }
                } catch (IOException e) {
                    log.debug("[WS] broadcast 发送失败 sid={} (异步移除)", s.getId());
                    failed.add(s);
                }
            });
            // 统一移除失败 / 关闭的连接
            for (WebSocketSession s : failed) {
                sessions.remove(s);
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

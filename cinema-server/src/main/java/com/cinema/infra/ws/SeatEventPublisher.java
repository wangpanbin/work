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
 *
 * <p><b>事件序号</b>: 每条事件带一个全局单调递增的 {@code seq}, 供客户端丢弃乱序到达的迟到事件。
 * 序号只能在<b>同一座位上</b>提供先后关系, 不能提供全序 —— 两个并发请求各自取到 seq=5 与 seq=6,
 * 谁先 PUBLISH 是不确定的。因此客户端必须按座位分别记录已应用序号; 若用一个整场次的序号做过滤,
 * 迟到的跨座位事件会被误杀, 那个座位的状态变更就彻底丢失。
 *
 * <p><b>能力边界(诚实记录)</b>: 取序号与发送不是一个原子操作。理论上存在「业务先完成但取序号慢」
 * 的请求被排在更晚的序号上, 此时客户端会信序号而非实际到达顺序, 仍可能产生一次短暂假可选。
 * 该残留由前端的防抖权威座位图重拉兜底 —— 两者并存, 不是二选一。
 *
 * <p><b>下游零改动</b>: SeatRedisSubscriber 是原样透传载荷字符串的, SeatWsHandler 也直接转发原串,
 * 所以序号只需在本类注入一次。
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
                    Map.of("type", type, "sessionId", sessionId, "seats", seats,
                            "seq", nextSeq()));
            redisTemplate.convertAndSend(RedisKeys.SEAT_EVENT_CHANNEL, payload);
        } catch (Exception e) {
            log.error("[WS] 座位事件发布失败 type={} session={} seats={}", type, sessionId, seats, e);
        }
    }

    /**
     * 取下一个全局事件序号。
     *
     * <p>返回 0 表示「拿不到序号」。前端必须把 seq=0 当作无序号处理(总是应用该事件),
     * 不能当成「比任何已应用序号都旧」—— 否则 0 &gt; 0 为假, 会把所有事件静默丢弃。
     */
    private long nextSeq() {
        try {
            Long seq = redisTemplate.opsForValue().increment(RedisKeys.SEAT_EVENT_SEQ);
            return seq == null ? 0L : seq;
        } catch (Exception e) {
            log.warn("[WS] 事件序号自增失败, 本条按 seq=0 发布(客户端视为无序号, 总是应用)", e);
            return 0L;
        }
    }
}

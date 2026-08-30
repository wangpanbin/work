package com.cinema.infra.delay;

import com.cinema.infra.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis ZSet 延迟队列实现
 *
 * <p>member=消息体(订单号), score=到期时间戳(毫秒)。
 * 取出即移除, 多实例下可能重复投递, 由消费方幂等(CAS)兜底;
 * 消息丢失由 OrderTimeoutCompensateJob 每分钟扫 DB 补偿(W3 实现)。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDelayQueue implements DelayQueue {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void offer(String message, long delayMillis) {
        double score = System.currentTimeMillis() + delayMillis;
        redisTemplate.opsForZSet().add(RedisKeys.ORDER_TIMEOUT_ZSET, message, score);
        if (log.isDebugEnabled()) {
            log.debug("[DelayQueue] offer {} delay={}ms", message, delayMillis);
        }
    }

    @Override
    public List<String> pollExpired(int batch) {
        double max = System.currentTimeMillis();
        Set<String> messages = redisTemplate.opsForZSet()
                .rangeByScore(RedisKeys.ORDER_TIMEOUT_ZSET, 0, max, 0, batch);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        redisTemplate.opsForZSet().remove(RedisKeys.ORDER_TIMEOUT_ZSET, messages.toArray());
        return new ArrayList<>(messages);
    }

    @Override
    public String name() {
        return "redis-zset";
    }
}

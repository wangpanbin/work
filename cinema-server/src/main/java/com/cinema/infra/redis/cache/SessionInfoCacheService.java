package com.cinema.infra.redis.cache;

import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.seat.cache.SessionInfoCacheVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 场次元数据缓存(Phase A-① 读路径优化)
 * <p>Key: cinema:session:info:{sid}, Hash 存储
 * <p>失效: AdminSessionController.create/update/delete 已有 redisTemplate.delete(key)
 * <p>TTL: 30 分钟兜底, 防止 admin 误改未触发清空
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionInfoCacheService {

    /** 缓存 TTL 兜底, 30 分钟. 即便 admin 改动未清空也最多 30min 陈旧. */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读缓存, 命中返回 Optional.of(vo), 未命中或反序列化失败返回 empty.
     * 反序列化失败按未命中处理, 走 DB 回源后覆盖.
     */
    public Optional<SessionInfoCacheVO> get(Long sessionId) {
        String key = RedisKeys.sessionInfo(sessionId);
        HashOperations<String, Object, Object> ops = redisTemplate.opsForHash();
        // HGETALL 一次取全部字段
        var entries = ops.entries(key);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        // Hash 内部已存为 JSON 字符串(便于读 / 写一致), 走单字段 JSON 解析
        try {
            String json = (String) entries.get("data");
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, SessionInfoCacheVO.class));
        } catch (Exception e) {
            log.warn("[SessionInfoCache] 反序列化失败, 走 DB 回源 sid={} err={}", sessionId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 写缓存, 单字段 JSON 序列化. 出错仅日志, 不阻塞主流程(主流程有自己的 DB 兜底).
     */
    public void put(SessionInfoCacheVO vo) {
        if (vo == null || vo.getSessionId() == null) {
            return;
        }
        String key = RedisKeys.sessionInfo(vo.getSessionId());
        try {
            String json = objectMapper.writeValueAsString(vo);
            redisTemplate.opsForHash().put(key, "data", json);
            redisTemplate.expire(key, TTL);
        } catch (JsonProcessingException e) {
            log.warn("[SessionInfoCache] 序列化失败 sid={} err={}", vo.getSessionId(), e.toString());
        } catch (Exception e) {
            log.warn("[SessionInfoCache] 写缓存失败 sid={} err={}", vo.getSessionId(), e.toString());
        }
    }

    /**
     * 显式失效. AdminSessionController 已经在 create/update/delete 中调用.
     * 这里再暴露一个公开方法便于测试和其他场景(如 hall 变更连带清空).
     */
    public void invalidate(Long sessionId) {
        try {
            redisTemplate.delete(RedisKeys.sessionInfo(sessionId));
        } catch (Exception e) {
            log.warn("[SessionInfoCache] 失效失败 sid={} err={}", sessionId, e.toString());
        }
    }
}

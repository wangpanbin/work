package com.cinema.common.aop;

import com.cinema.common.annotation.Idempotent;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 幂等切面 — SpEL 解析 key, Redis SETNX 判定是否重复提交。
 * <p>E5 实现: 命中抛 BizException(code=40900)。
 * <p>注意: 业务侧仍需 DB 唯一约束兜底(本注解只防短时间重试)。
 * <p>SpEL 求值与限流切面共用 {@link SpelKeyResolver}, 两处不再各写一份副本。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final StringRedisTemplate redisTemplate;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String key = "cinema:idempotent:" + SpelKeyResolver.eval(pjp, idempotent.key(), "幂等");
        Boolean firstTime = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(idempotent.ttl()));
        if (Boolean.FALSE.equals(firstTime)) {
            log.warn("[幂等] key={} 命中, 拒绝重复提交", key);
            throw new BizException(ResultCode.IDEMPOTENT_CONFLICT.getCode(), idempotent.message(), null);
        }
        try {
            return pjp.proceed();
        } catch (Exception e) {
            // 业务失败时, 删除幂等键, 允许重试
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }
}

package com.cinema.common.aop;

import com.cinema.common.annotation.RateLimit;
import com.cinema.common.exception.BizException;
import com.cinema.common.ratelimit.RedisSlidingWindow;
import com.cinema.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 限流切面 — 解析 @RateLimit 的 SpEL key, 调 RedisSlidingWindow 判定。
 * <p>E4 实现: 拒绝时抛 BizException(code=42900), 由 GlobalExceptionHandler 统一处理。
 * <p>SpEL 求值与幂等切面共用 {@link SpelKeyResolver}, 两处不再各写一份副本。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedisSlidingWindow slidingWindow;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String bucketKey = "cinema:ratelimit:" + SpelKeyResolver.eval(pjp, rateLimit.key(), "限流");
        long windowMs = TimeUnit.MILLISECONDS.convert(rateLimit.window(), rateLimit.unit());
        // spec #17 限流分桶:bucketKey 含 ":anon:" 且 anonymousPermits >= 0 时,匿名走专属 permits
        int effectivePermits = (bucketKey.contains(":anon:") && rateLimit.anonymousPermits() >= 0)
                ? rateLimit.anonymousPermits()
                : rateLimit.permits();
        if (!slidingWindow.tryAcquire(bucketKey, effectivePermits, windowMs)) {
            log.warn("[限流] bucket={} permits={} window={}ms", bucketKey, effectivePermits, windowMs);
            throw new BizException(ResultCode.RATE_LIMIT.getCode(), rateLimit.message(), null);
        }
        return pjp.proceed();
    }
}

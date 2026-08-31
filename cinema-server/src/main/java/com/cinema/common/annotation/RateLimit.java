package com.cinema.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 限流注解 — Redis 滑动窗口实现, 由 RateLimitAspect 拦截.
 * <p>E4 用法: 锁座/支付/退票接口加注解保护.
 * <p>key 为 SpEL 表达式, 拼接后作为 Redis 桶 key.
 *
 * <pre>
 *   {@code
 *   @RateLimit(key = "#userId + ':lock:' + #dto.sessionId", permits = 5, window = 1)
 *   public R<LockResultVO> lock(LockSeatsDTO dto) { ... }
 *   }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** SpEL 表达式, 用于拼接桶 key */
    String key();

    /** 桶内允许的请求数 */
    int permits() default 1;

    /** 时间窗口大小 */
    int window() default 1;

    /** 时间单位 */
    TimeUnit unit() default TimeUnit.SECONDS;

    /** 拒绝时的提示 */
    String message() default "请求过于频繁,请稍后重试";
}

package com.cinema.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等键注解 — Redis SETNX 实现, 由 IdempotentAspect 拦截.
 * <p>E5 用法: 锁座/支付接口加注解, 短时间内的重复请求直接拒绝.
 * <p>key 为 SpEL 表达式, 拼接后作为 Redis 键.
 * <p>TTL 内 SETNX 命中即拒绝, 业务由 DB 唯一约束兜底.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** SpEL 表达式, 拼接后作为 Redis 键 */
    String key();

    /** 键过期时间(秒), 默认 2s */
    long ttl() default 2;

    /** 重复提交时的提示 */
    String message() default "请勿重复提交";
}

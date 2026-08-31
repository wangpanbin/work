package com.cinema.common.aop;

import com.cinema.common.annotation.Idempotent;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 幂等切面 — SpEL 解析 key, Redis SETNX 判定是否重复提交.
 * <p>E5 实现: 命中抛 BizException(code=40900).
 * <p>注意: 业务侧仍需 DB 唯一约束兜底(本注解只防短时间重试).
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final StringRedisTemplate redisTemplate;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String key = "cinema:idempotent:" + evalKey(pjp, idempotent.key());
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

    private String evalKey(ProceedingJoinPoint pjp, String spel) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Object[] args = pjp.getArgs();
        String[] paramNames = sig.getParameterNames();
        EvaluationContext ctx = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }
        try {
            Expression exp = parser.parseExpression(spel);
            Object value = exp.getValue(ctx);
            return value == null ? "anon" : value.toString();
        } catch (Exception e) {
            log.warn("[幂等] SpEL 解析失败 key={} err={}", spel, e.toString());
            return "anon";
        }
    }
}

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
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 限流切面 — 解析 @RateLimit 的 SpEL key, 调 RedisSlidingWindow 判定.
 * <p>E4 实现: 拒绝时抛 BizException(code=42900), 由 GlobalExceptionHandler 统一处理.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RedisSlidingWindow slidingWindow;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String bucketKey = "cinema:ratelimit:" + evalKey(pjp, rateLimit.key());
        long windowMs = TimeUnit.MILLISECONDS.convert(rateLimit.window(), rateLimit.unit());
        if (!slidingWindow.tryAcquire(bucketKey, rateLimit.permits(), windowMs)) {
            log.warn("[限流] bucket={} permits={} window={}ms", bucketKey, rateLimit.permits(), windowMs);
            throw new BizException(ResultCode.RATE_LIMIT.getCode(), rateLimit.message(), null);
        }
        return pjp.proceed();
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
            log.warn("[限流] SpEL 解析失败 key={} err={}", spel, e.toString());
            return "anon";
        }
    }
}

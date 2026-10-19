package com.cinema.common.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * SpEL key 求值 —— 限流与幂等两个切面共用的同一份实现。
 *
 * <p><b>为什么抽出来</b>: {@code RateLimitAspect} 与 {@code IdempotentAspect}
 * 此前各写了一份逐字相同的 {@code evalKey}。两份副本意味着改一处(比如支持
 * {@code #p0} 之类的参数下标)很容易漏掉另一处, 而漏掉的那份会在某个注解上
 * 静默退化成 {@code anon} 桶 —— 所有用户挤进同一个桶, 限流变成全局限流。
 *
 * <p><b>求值失败的兜底</b>: SpEL 解析/求值异常一律返回 {@link #ANON},
 * 并打一条 warn 日志。宁可退化成「匿名桶」也不要让切面抛异常把业务请求打断。
 *
 * <p><b>注意</b>: 参数名与实参个数不匹配时抛出的 {@code ArrayIndexOutOfBoundsException}
 * <b>刻意不吞</b> —— 那说明注解里的 SpEL 引用了不存在的参数名, 属于编程错误,
 * 必须暴露出来。吞掉它会让所有用户静默挤进同一个桶。
 */
@Slf4j
public final class SpelKeyResolver {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    /** 求值为 null(典型是 userId 未登录)或解析失败时的兜底桶名。 */
    public static final String ANON = "anon";

    private SpelKeyResolver() {
    }

    /**
     * 求值 SpEL key。
     *
     * @param pjp  切点
     * @param spel SpEL 表达式
     * @param tag  日志前缀(「限流」/「幂等」), 只影响日志内容
     * @return 求值结果的字符串形式; 为 null 或解析失败时返回 {@link #ANON}
     */
    public static String eval(ProceedingJoinPoint pjp, String spel, String tag) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Object[] args = pjp.getArgs();
        String[] paramNames = sig.getParameterNames();
        EvaluationContext ctx = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }
        try {
            Expression exp = PARSER.parseExpression(spel);
            Object value = exp.getValue(ctx);
            return value == null ? ANON : value.toString();
        } catch (Exception e) {
            log.warn("[{}] SpEL 解析失败 key={} err={}", tag, spel, e.toString());
            return ANON;
        }
    }
}

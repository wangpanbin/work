package com.cinema.common.aop;

import com.cinema.common.annotation.RateLimit;
import com.cinema.common.exception.BizException;
import com.cinema.common.ratelimit.RedisSlidingWindow;
import com.cinema.common.result.ResultCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5 — RateLimitAspect 单元测试(spec §9.1 + §6.3 + §6.2).
 *
 * <p>覆盖 spec §9.1 第 2 条:限流命中返 42900(从切面层验证 — 切面抛 BizException(42900),
 * 由 GlobalExceptionHandler 转 R.fail(42900),前端按 code 处理).
 *
 * <p>SpEL key 测试:
 * <ul>
 *   <li>userId 非 null → 桶 key 含 userId</li>
 *   <li>userId null → 短路 'anon' 字面量(spec Q3 决策)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitAspectTest {

    @Mock private RedisSlidingWindow slidingWindow;
    @Mock private ProceedingJoinPoint pjp;
    @Mock private MethodSignature sig;

    private RateLimitAspect aspect;

    @Test
    @DisplayName("限流未命中(tryAcquire=true)→ pjp.proceed() 被调,正常返回")
    void givenWithinLimit_whenAround_thenProceeds() throws Throwable {
        aspect = new RateLimitAspect(slidingWindow);
        when(slidingWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");
        setupJoinPoint("user-1");

        RateLimit annotation = makeRateLimit(
                "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
                10, 1, TimeUnit.MINUTES);

        Object result = aspect.around(pjp, annotation);

        assertThat(result).isEqualTo("ok");
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("限流命中(tryAcquire=false)→ 抛 BizException(42900),pjp.proceed() 不被调")
    void givenLimitExceeded_whenAround_thenThrowsBizException42900() throws Throwable {
        aspect = new RateLimitAspect(slidingWindow);
        when(slidingWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(false);
        setupJoinPoint("user-1");

        RateLimit annotation = makeRateLimit(
                "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
                10, 1, TimeUnit.MINUTES);

        assertThatThrownBy(() -> aspect.around(pjp, annotation))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", ResultCode.RATE_LIMIT.getCode()); // 42900
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("userId==null 时 SpEL 短路 'anon' 字面量,桶 key == 'anon:chat'")
    void givenNullUserId_spelShortCircuitsToAnon() throws Throwable {
        aspect = new RateLimitAspect(slidingWindow);
        when(slidingWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");
        setupJoinPoint(null); // null userId

        RateLimit annotation = makeRateLimit(
                "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
                10, 1, TimeUnit.MINUTES);

        aspect.around(pjp, annotation);

        // RateLimitAspect.around 在 bucketKey 前加 "cinema:ratelimit:" 前缀
        verify(slidingWindow).tryAcquire(eq("cinema:ratelimit:anon:chat"), eq(10), anyLong());
    }

    // ============ helpers ============

    /**
     * Mockito mock MethodSignature:只 stub 必要的(其他返 null/0).
     * SpEL 只读 sig.getParameterNames() + sig.getMethod()(后者没用到).
     */
    private void setupJoinPoint(String userId) {
        when(sig.getParameterNames()).thenReturn(new String[]{"userId"});
        Method stubMethod = stubMethod();
        when(sig.getMethod()).thenReturn(stubMethod);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(new Object[]{userId});
    }

    /** 任意静态方法占位 — SpEL 只看 args 数组,不检查 method 本身. */
    private static Method stubMethod() {
        try {
            return RateLimitAspectTest.class.getDeclaredMethod("stubHolder");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static void stubHolder() { /* no-op */ }

    /** 反射构造一个真实 RateLimit 注解(spec §6.3:key + permits + window + unit + message). */
    private static RateLimit makeRateLimit(String key, int permits, int window, TimeUnit unit) {
        return new RateLimit() {
            @Override
            public Class<? extends Annotation> annotationType() { return RateLimit.class; }
            @Override public String key() { return key; }
            @Override public int permits() { return permits; }
            @Override public int window() { return window; }
            @Override public TimeUnit unit() { return unit; }
            @Override public String message() { return "限流测试"; }
        };
    }
}
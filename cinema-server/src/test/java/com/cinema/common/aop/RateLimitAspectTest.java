package com.cinema.common.aop;

import com.cinema.common.annotation.RateLimit;
import com.cinema.common.context.UserContext;
import com.cinema.common.exception.BizException;
import com.cinema.common.ratelimit.RedisSlidingWindow;
import com.cinema.common.result.ResultCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
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
        setupJoinPoint(1L);

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
        setupJoinPoint(1L);

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

    // ============ spec #17 限流分桶测试(RED 阶段)============

    @Test
    @DisplayName("[spec #17] 匿名走 anonymousPermits=2,登录走 permits=10(同一注解,userId 切换)")
    void givenTieredPermits_whenAround_thenPicksByUserIdPresence() throws Throwable {
        aspect = new RateLimitAspect(slidingWindow);
        when(slidingWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");

        RateLimit annotation = makeRateLimit(
                "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
                /* permits */ 10,
                /* anonymousPermits */ 2,
                1, TimeUnit.MINUTES);

        // 1) 匿名路径
        setupJoinPoint(null);
        aspect.around(pjp, annotation);
        verify(slidingWindow).tryAcquire(eq("cinema:ratelimit:anon:chat"), eq(2), anyLong());

        // 2) 登录路径(同一注解,同一 aspect 实例)
        // SpEL `T(UserContext).userId() ?: 'anon' + ':chat'` 在 userId 非空时返 userId.toString(),
        // 由于 SpEL 优先级,登录桶 key 为 "cinema:ratelimit:42"(无 :chat 后缀)— 与匿名桶
        // "cinema:ratelimit:anon:chat" 不对称,但这是 spec §6.3 既定行为(spec 修复不在本 ticket)
        setupJoinPoint(42L);
        aspect.around(pjp, annotation);
        verify(slidingWindow).tryAcquire(eq("cinema:ratelimit:42"), eq(10), anyLong());
    }

    @Test
    @DisplayName("[spec #17] anonymousPermits=-1(默认)时回退 permits,既有行为不变")
    void givenDefaultAnonymousPermits_whenAround_thenFallsBackToPermits() throws Throwable {
        aspect = new RateLimitAspect(slidingWindow);
        when(slidingWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");

        RateLimit annotation = makeRateLimit(
                "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
                /* permits */ 10,
                /* anonymousPermits */ -1,
                1, TimeUnit.MINUTES);

        // 匿名路径仍用 permits=10(回退)
        setupJoinPoint(null);
        aspect.around(pjp, annotation);
        verify(slidingWindow).tryAcquire(eq("cinema:ratelimit:anon:chat"), eq(10), anyLong());

        // 登录路径也用 permits=10(spec §6.3 现状 key 为 "cinema:ratelimit:7")
        setupJoinPoint(7L);
        aspect.around(pjp, annotation);
        verify(slidingWindow).tryAcquire(eq("cinema:ratelimit:7"), eq(10), anyLong());
    }

    @Test
    @DisplayName("[spec #17] anonymousPermits=0 时也合法 — 匿名桶完全拒绝(spec 不强制 ChatController 启用 0)")
    void givenZeroAnonymousPermits_whenAround_thenAnonUsesZero() throws Throwable {
        aspect = new RateLimitAspect(slidingWindow);
        when(slidingWindow.tryAcquire(anyString(), anyInt(), anyLong())).thenReturn(true);
        when(pjp.proceed()).thenReturn("ok");

        RateLimit annotation = makeRateLimit(
                "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
                /* permits */ 10,
                /* anonymousPermits */ 0,
                1, TimeUnit.MINUTES);

        setupJoinPoint(null);
        aspect.around(pjp, annotation);
        verify(slidingWindow).tryAcquire(eq("cinema:ratelimit:anon:chat"), eq(0), anyLong());
    }

    // ============ helpers ============

    /**
     * Mockito mock MethodSignature:只 stub 必要的(其他返 null/0).
     * SpEL 只读 sig.getParameterNames() + sig.getMethod()(后者没用到).
     *
     * <p><b>spec #17 修订</b>:SpEL key 走 {@code T(UserContext).userId()},所以这里同时设
     * UserContext ThreadLocal,模拟"已登录/匿名"两种场景。{@code null} 表示匿名(不设 ThreadLocal)。
     */
    private void setupJoinPoint(Long userId) {
        if (userId != null) {
            UserContext.set(userId, "stub-user", 0);
        }
        when(sig.getParameterNames()).thenReturn(new String[]{"userId"});
        Method stubMethod = stubMethod();
        when(sig.getMethod()).thenReturn(stubMethod);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(new Object[]{userId == null ? null : userId.toString()});
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
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
        return makeRateLimit(key, permits, /* anonymousPermits */ -1, window, unit);
    }

    /** spec #17:支持 anonymousPermits 字段的 makeRateLimit 重载. */
    private static RateLimit makeRateLimit(String key, int permits, int anonymousPermits, int window, TimeUnit unit) {
        return new RateLimit() {
            @Override
            public Class<? extends Annotation> annotationType() { return RateLimit.class; }
            @Override public String key() { return key; }
            @Override public int permits() { return permits; }
            @Override public int window() { return window; }
            @Override public TimeUnit unit() { return unit; }
            @Override public String message() { return "限流测试"; }
            @Override public int anonymousPermits() { return anonymousPermits; }
        };
    }
}
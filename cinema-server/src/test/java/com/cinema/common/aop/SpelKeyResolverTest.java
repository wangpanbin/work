package com.cinema.common.aop;

import com.cinema.common.annotation.IdempotencyKeys;
import com.cinema.common.context.UserContext;
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

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link SpelKeyResolver} 单元测试 —— 限流与幂等两个切面共用的 SpEL 求值。
 *
 * <p>锁住三件事:
 * <ol>
 *   <li>userId 存在 → 求值为 userId 的字符串</li>
 *   <li>userId 为 null → 求值为 {@code anon}(不抛 NPE)</li>
 *   <li>SpEL 解析失败 → 兜底 {@code anon}, 切面不被打断</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpelKeyResolverTest {

    @Mock private ProceedingJoinPoint pjp;
    @Mock private MethodSignature sig;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("userId 存在 → 求值为 userId 字符串")
    void userIdPresent_resolvesToUserId() {
        UserContext.set(42L, "u42", 0);
        setupJoinPoint("42");

        String key = SpelKeyResolver.eval(pjp, IdempotencyKeys.RATE_CHAT_MESSAGE_BY_USER, "限流");

        assertThat(key).isEqualTo("42:chat");
    }

    @Test
    @DisplayName("userId 为 null → 求值为 'anon'(不抛 NPE)")
    void userIdNull_resolvesToAnon() {
        setupJoinPoint(null);

        String key = SpelKeyResolver.eval(pjp, IdempotencyKeys.RATE_CHAT_MESSAGE_BY_USER, "限流");

        assertThat(key).isEqualTo("anon:chat");
    }

    @Test
    @DisplayName("SpEL 解析失败 → 兜底 'anon', 不抛异常打断切面")
    void brokenSpel_fallsBackToAnon() {
        setupJoinPoint("42");

        String key = SpelKeyResolver.eval(pjp, "this is not a valid SpEL ((", "限流");

        assertThat(key).isEqualTo(SpelKeyResolver.ANON);
    }

    @Test
    @DisplayName("求值为 null → 兜底 'anon'")
    void nullValue_fallsBackToAnon() {
        setupJoinPoint("42");

        String key = SpelKeyResolver.eval(pjp, "#missing", "幂等");

        assertThat(key).isEqualTo(SpelKeyResolver.ANON);
    }

    private void setupJoinPoint(String arg) {
        when(sig.getParameterNames()).thenReturn(new String[]{"arg"});
        when(sig.getMethod()).thenReturn(stubMethod());
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(new Object[]{arg});
    }

    /** 任意静态方法占位 — SpEL 只看 args 数组,不检查 method 本身。 */
    private static Method stubMethod() {
        try {
            return SpelKeyResolverTest.class.getDeclaredMethod("stubHolder");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private static void stubHolder() { /* no-op */ }
}

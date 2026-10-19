package com.cinema.common.annotation;

import com.cinema.modules.chat.controller.ChatOrderController;
import com.cinema.modules.order.controller.OrderController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdempotencyKeys drift detection — 锁定各 controller 端点的 SpEL key
 * 与 {@link IdempotencyKeys} 常量一致. 防止"在 controller 改了 SpEL 忘了同步常量"的漂移.
 *
 * <p>#7 收尾: 这是一个 lock-in test, 锁定当前 SpEL 字符串. 如果未来 controller
 * 改 SpEL(比如加新参数), 测试会先 fail, 提醒同步 IdempotencyKeys 常量。
 *
 * <p><b>W7.1 升级为自动扫描</b>: 原来这里是逐方法手写反射断言 —— 只覆盖了当时已知的
 * 7 个端点, 新加的端点若忘了引用常量, 测试根本看不见。现在额外扫描
 * {@code modules/**} 下所有类的所有方法, 断言**每一个** {@code @Idempotent} /
 * {@code @RateLimit} 的 key 都取自 {@link IdempotencyKeys} 的某个常量。
 * 逐方法断言保留 —— 它锁的是「哪个端点该用哪个常量」, 自动扫描锁的是
 * 「不许出现没走常量表的 SpEL」, 两者互补。
 */
class IdempotencyKeysTest {

    private static String getIdempotentKey(String methodName) {
        try {
            Method m = OrderController.class.getMethod(methodName, getParamTypes(methodName));
            Idempotent ann = m.getAnnotation(Idempotent.class);
            return ann == null ? null : ann.key();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static String getRateLimitKey(String methodName) {
        try {
            Method m = OrderController.class.getMethod(methodName, getParamTypes(methodName));
            RateLimit ann = m.getAnnotation(RateLimit.class);
            return ann == null ? null : ann.key();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?>[] getParamTypes(String methodName) {
        return switch (methodName) {
            case "lock" -> new Class<?>[]{com.cinema.modules.order.dto.LockSeatsDTO.class};
            case "pay", "cancel", "refund" -> new Class<?>[]{String.class};
            default -> new Class<?>[0];
        };
    }

    @Test
    @DisplayName("OrderController.lock: @Idempotent key 与 IdempotencyKeys.LOCK_BY_USER_SESSION_SEATS 一致")
    void lockIdempotentKeyMatches() {
        assertThat(getIdempotentKey("lock")).isEqualTo(IdempotencyKeys.LOCK_BY_USER_SESSION_SEATS);
    }

    @Test
    @DisplayName("OrderController.lock: @RateLimit key 与 RATE_LOCK_BY_USER_SESSION 一致")
    void lockRateLimitKeyMatches() {
        assertThat(getRateLimitKey("lock")).isEqualTo(IdempotencyKeys.RATE_LOCK_BY_USER_SESSION);
    }

    @Test
    @DisplayName("OrderController.pay: @Idempotent key 与 PAY_BY_ORDER_NO 一致")
    void payIdempotentKeyMatches() {
        assertThat(getIdempotentKey("pay")).isEqualTo(IdempotencyKeys.PAY_BY_ORDER_NO);
    }

    @Test
    @DisplayName("OrderController.pay: @RateLimit key 与 RATE_PAY_BY_USER_ORDER 一致")
    void payRateLimitKeyMatches() {
        assertThat(getRateLimitKey("pay")).isEqualTo(IdempotencyKeys.RATE_PAY_BY_USER_ORDER);
    }

    @Test
    @DisplayName("OrderController.cancel: @RateLimit key 与 RATE_CANCEL_BY_USER_ORDER 一致")
    void cancelRateLimitKeyMatches() {
        assertThat(getRateLimitKey("cancel")).isEqualTo(IdempotencyKeys.RATE_CANCEL_BY_USER_ORDER);
    }

    @Test
    @DisplayName("OrderController.refund: @Idempotent key 与 REFUND_BY_ORDER_NO 一致")
    void refundIdempotentKeyMatches() {
        assertThat(getIdempotentKey("refund")).isEqualTo(IdempotencyKeys.REFUND_BY_ORDER_NO);
    }

    @Test
    @DisplayName("OrderController.refund: @RateLimit key 与 RATE_REFUND_BY_USER_ORDER 一致")
    void refundRateLimitKeyMatches() {
        assertThat(getRateLimitKey("refund")).isEqualTo(IdempotencyKeys.RATE_REFUND_BY_USER_ORDER);
    }

    // ================================================================
    // ADR-0004: 对话下单端点的 SpEL 也要锁住(否则改 controller 忘了同步常量)
    // ================================================================

    private static String chatIdempotentKey(String methodName) {
        try {
            Method m = ChatOrderController.class.getMethod(methodName, chatParamTypes(methodName));
            Idempotent ann = m.getAnnotation(Idempotent.class);
            return ann == null ? null : ann.key();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static String chatRateLimitKey(String methodName) {
        try {
            Method m = ChatOrderController.class.getMethod(methodName, chatParamTypes(methodName));
            RateLimit ann = m.getAnnotation(RateLimit.class);
            return ann == null ? null : ann.key();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?>[] chatParamTypes(String methodName) {
        return switch (methodName) {
            case "createProposal" -> new Class<?>[]{com.cinema.modules.chat.order.dto.OrderProposalDTO.class};
            case "confirm" -> new Class<?>[]{String.class};
            default -> new Class<?>[0];
        };
    }

    @Test
    @DisplayName("ChatOrderController.confirm: @Idempotent key 与 CHAT_ORDER_CONFIRM 一致(键就是 proposalId)")
    void chatConfirmIdempotentKeyMatches() {
        assertThat(chatIdempotentKey("confirm")).isEqualTo(IdempotencyKeys.CHAT_ORDER_CONFIRM);
    }

    @Test
    @DisplayName("ChatOrderController.confirm: @RateLimit key 与 RATE_CHAT_CONFIRM_BY_USER 一致")
    void chatConfirmRateLimitKeyMatches() {
        assertThat(chatRateLimitKey("confirm")).isEqualTo(IdempotencyKeys.RATE_CHAT_CONFIRM_BY_USER);
    }

    @Test
    @DisplayName("ChatOrderController.createProposal: @RateLimit key 与 RATE_CHAT_PROPOSAL_BY_USER 一致")
    void chatProposalRateLimitKeyMatches() {
        assertThat(chatRateLimitKey("createProposal"))
                .isEqualTo(IdempotencyKeys.RATE_CHAT_PROPOSAL_BY_USER);
    }

    @Test
    @DisplayName("对话下单的限流桶不按 proposalId / sessionId 分桶 —— 否则限流触发不了")
    void chatRateLimitBuckets_arePerUser() {
        // 提案一次性消费, 把 proposalId 拼进桶键 -> 「3 次/分钟」永不触发
        assertThat(IdempotencyKeys.RATE_CHAT_CONFIRM_BY_USER).doesNotContain("proposalId");
        // 换场次就换桶 -> 「10 次/分钟」实际是每场次 10 次
        assertThat(IdempotencyKeys.RATE_CHAT_PROPOSAL_BY_USER).doesNotContain("sessionId");
    }

    // ================================================================
    // W7.1 自动扫描: modules/** 下每个 @Idempotent / @RateLimit 都必须引用常量
    // ================================================================

    /** 扫描到的一处注解用法。 */
    private record Usage(String className, String methodName, String kind, String key) {
        @Override
        public String toString() {
            return className + "#" + methodName + " (" + kind + ")";
        }
    }

    @Test
    @DisplayName("modules/** 下每个 @Idempotent 的 key 都取自 IdempotencyKeys 常量")
    void everyIdempotentKeyComesFromConstants() {
        List<Usage> usages = scan(Idempotent.class, "@Idempotent");
        // 扫描本身不能是空转 —— 否则这道闸门恒真
        assertThat(usages).isNotEmpty();
        assertThat(usages)
                .extracting(Usage::className)
                .contains("com.cinema.modules.order.controller.OrderController",
                        "com.cinema.modules.chat.controller.ChatOrderController");

        Set<String> constants = constantValues();
        assertThat(usages)
                .allSatisfy(u -> assertThat(constants)
                        .as("%s 的 @Idempotent key 必须引用 IdempotencyKeys 常量", u)
                        .contains(u.key()));
    }

    @Test
    @DisplayName("modules/** 下每个 @RateLimit 的 key 都取自 IdempotencyKeys 常量")
    void everyRateLimitKeyComesFromConstants() {
        List<Usage> usages = scan(RateLimit.class, "@RateLimit");
        assertThat(usages).isNotEmpty();
        assertThat(usages)
                .extracting(Usage::className)
                .contains("com.cinema.modules.order.controller.OrderController",
                        "com.cinema.modules.chat.controller.ChatOrderController",
                        "com.cinema.modules.chat.controller.ChatController");

        Set<String> constants = constantValues();
        assertThat(usages)
                .allSatisfy(u -> assertThat(constants)
                        .as("%s 的 @RateLimit key 必须引用 IdempotencyKeys 常量", u)
                        .contains(u.key()));
    }

    /** 收集 IdempotencyKeys 里所有 public static final String 常量的值。 */
    private static Set<String> constantValues() {
        return Arrays.stream(IdempotencyKeys.class.getDeclaredFields())
                .filter(f -> f.getType() == String.class)
                .filter(f -> Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()))
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                })
                .collect(Collectors.toSet());
    }

    /** 扫描 modules/** 下所有类的方法, 收集带指定注解的用法。 */
    private static List<Usage> scan(Class<? extends Annotation> annType, String kind) {
        Path root = classesRoot();
        Path modulesRoot = root.resolve("com").resolve("cinema").resolve("modules");
        List<Usage> usages = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(modulesRoot)) {
            List<Path> classFiles = stream
                    .filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .toList();
            for (Path p : classFiles) {
                String className = root.relativize(p).toString()
                        .replace(File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                Class<?> clazz = Class.forName(className, false, IdempotencyKeysTest.class.getClassLoader());
                for (Method m : clazz.getDeclaredMethods()) {
                    Annotation ann = m.getAnnotation(annType);
                    if (ann == null) {
                        continue;
                    }
                    String key = (String) annType.getMethod("key").invoke(ann);
                    usages.add(new Usage(className, m.getName(), kind, key));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("扫描 modules/** 失败", e);
        }
        return usages;
    }

    /** 编译产物根目录(target/classes)。 */
    private static Path classesRoot() {
        try {
            URL url = OrderController.class.getProtectionDomain().getCodeSource().getLocation();
            return Paths.get(url.toURI());
        } catch (Exception e) {
            throw new AssertionError("定位编译产物目录失败", e);
        }
    }
}

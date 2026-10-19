package com.cinema.common.annotation;

import com.cinema.modules.chat.controller.ChatOrderController;
import com.cinema.modules.order.controller.OrderController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdempotencyKeys drift detection — 锁定 OrderController 4 个端点的 SpEL key
 * 与 {@link IdempotencyKeys} 常量一致. 防止"在 controller 改了 SpEL 忘了同步常量"的漂移。
 *
 * <p>#7 收尾: 这是一个 lock-in test, 锁定当前 SpEL 字符串. 如果未来 controller
 * 改 SpEL(比如加新参数), 测试会先 fail, 提醒同步 IdempotencyKeys 常量。
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
}
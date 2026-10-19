package com.cinema.interceptor;

import com.cinema.common.context.UserContext;
import com.cinema.common.result.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthPolicy} 单元测试 —— 鉴权判定口径的唯一一份实现。
 *
 * <p>锁住两件事:
 * <ol>
 *   <li>未登录 → 40101(不是 40301)。前端按 code 分流, 换码就换提示语。</li>
 *   <li>非管理员 → 40301(不是 40101)。</li>
 * </ol>
 */
class AuthPolicyTest {

    private final AuthPolicy policy = new AuthPolicy();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("未登录 → 登录校验返 UNAUTHORIZED, 管理端校验返 FORBIDDEN")
    void anonymous_isRejectedByBoth() {
        assertThat(policy.rejectionForLogin()).isEqualTo(ResultCode.UNAUTHORIZED);
        assertThat(policy.rejectionForAdmin()).isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    @DisplayName("已登录但不是管理员 → 登录校验放行, 管理端校验返 FORBIDDEN")
    void loggedInButNotAdmin() {
        UserContext.set(42L, "u42", 0);

        assertThat(policy.rejectionForLogin()).isNull();
        assertThat(policy.rejectionForAdmin()).isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    @DisplayName("管理员(role=1)→ 两项校验都放行")
    void admin_passesBoth() {
        UserContext.set(1L, "admin", 1);

        assertThat(policy.rejectionForLogin()).isNull();
        assertThat(policy.rejectionForAdmin()).isNull();
    }
}

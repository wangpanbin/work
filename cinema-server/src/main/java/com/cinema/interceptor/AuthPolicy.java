package com.cinema.interceptor;

import com.cinema.common.context.UserContext;
import com.cinema.common.result.ResultCode;
import org.springframework.stereotype.Component;

/**
 * 鉴权策略 —— 把「谁能进」这件事从三个拦截器里抽出来, 只留一份判定口径。
 *
 * <p>三个拦截器此前各写一遍「读 UserContext → 比对 → 决定返哪个业务码」,
 * 判定逻辑与返回码散在三处。改一处漏两处就会出现「管理端接口忘了判角色」
 * 或「登录校验返了 403 而不是 401」这类只在特定路径上显形的偏差。
 *
 * <p><b>本类只做判定</b>, 响应怎么写由 {@link ApiResponseWriter} 负责 ——
 * 两件事分开, 判定可以被单测直接断言, 不必去解析 HTTP 响应体。
 *
 * <p><b>判定依据全部来自 {@link UserContext}</b>, 而 UserContext 由
 * {@code JwtInterceptor} 从 token 写入。判定本身不碰 token, 所以
 * 「token 解析失败」与「token 有效但不是管理员」是两条不同的拒绝路径。
 */
@Component
public class AuthPolicy {

    /**
     * 登录态校验。
     *
     * @return 不通过时返回要写给客户端的业务码({@link ResultCode#UNAUTHORIZED}),
     *         通过时返回 {@code null}
     */
    public ResultCode rejectionForLogin() {
        return UserContext.userId() == null ? ResultCode.UNAUTHORIZED : null;
    }

    /**
     * 管理端校验。
     *
     * @return 不通过时返回 {@link ResultCode#FORBIDDEN}, 通过时返回 {@code null}
     */
    public ResultCode rejectionForAdmin() {
        return UserContext.isAdmin() ? null : ResultCode.FORBIDDEN;
    }
}

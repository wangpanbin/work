package com.cinema.common.web;

/**
 * 列表接口分页入参钳制。
 *
 * <p>P2 修复(2026-10-11 稳定性测试): 各 Controller 原先直接透传
 * {@code @RequestParam int page/size}, 没有边界约束。MyBatis-Plus 在 {@code size <= 0} 时
 * <b>会静默关闭分页</b>并把 {@code total} 记成 0 —— 实测:
 * <ul>
 *   <li>{@code GET /api/movies?page=1&size=-1} → 返回全部 20 条, 且 {@code total=0}(元数据错误)</li>
 *   <li>{@code GET /api/orders/my?page=1&size=-5} → 返回全部订单, {@code total=0}</li>
 *   <li>{@code size=100000} → 上限不设防, 一次拉全表</li>
 * </ul>
 * 数据量小的时候只是"行为不一致", 但 {@code order}/{@code session} 会持续增长,
 * 一次 {@code size=-1} 就能把整张表读进内存并序列化返回。
 */
public final class PageParams {

    /** 单页最大条数。前端实际使用到 size=100({@code SessionManage.vue}), 100 不会截断现有页面。 */
    public static final int MAX_SIZE = 100;

    private PageParams() {
    }

    /** 页码钳到 >= 1(0 / 负数都归一到第 1 页)。 */
    public static int page(int page) {
        return Math.max(1, page);
    }

    /** 每页条数钳到 [1, {@link #MAX_SIZE}](<=0 不再等于"关闭分页")。 */
    public static int size(int size) {
        return Math.min(Math.max(1, size), MAX_SIZE);
    }
}

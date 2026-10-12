package com.cinema.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2 修复回归闸门(2026-10-11 稳定性测试): 分页入参钳制。
 *
 * <p>修复前各 Controller 直接透传 {@code page}/{@code size}, 而 MyBatis-Plus 在
 * {@code size <= 0} 时会<b>静默关闭分页</b>并把 {@code total} 记成 0。实测:
 * {@code GET /api/movies?page=1&size=-1} 返回全部 20 条且 {@code total=0};
 * {@code GET /api/orders/my?page=1&size=-5} 返回全部订单。
 */
class PageParamsTest {

    @Test
    @DisplayName("size<=0 不再等于'关闭分页', 归一到 1")
    void size_nonPositive_clampedToOne() {
        assertThat(PageParams.size(0)).isEqualTo(1);
        assertThat(PageParams.size(-1)).isEqualTo(1);
        assertThat(PageParams.size(-100000)).isEqualTo(1);
    }

    @Test
    @DisplayName("size 超上限钳到 MAX_SIZE")
    void size_overMax_clampedToMax() {
        assertThat(PageParams.size(100000)).isEqualTo(PageParams.MAX_SIZE);
        assertThat(PageParams.size(PageParams.MAX_SIZE + 1)).isEqualTo(PageParams.MAX_SIZE);
    }

    @Test
    @DisplayName("正常 size 原样透传(不改变现有页面行为)")
    void size_normal_passesThrough() {
        assertThat(PageParams.size(1)).isEqualTo(1);
        assertThat(PageParams.size(10)).isEqualTo(10);
        assertThat(PageParams.size(20)).isEqualTo(20);
        // 前端 SessionManage.vue 实际用到 size=100, 不能被截断
        assertThat(PageParams.size(100)).isEqualTo(100);
    }

    @Test
    @DisplayName("page<=0 归一到第 1 页")
    void page_nonPositive_clampedToOne() {
        assertThat(PageParams.page(0)).isEqualTo(1);
        assertThat(PageParams.page(-1)).isEqualTo(1);
        assertThat(PageParams.page(1)).isEqualTo(1);
        assertThat(PageParams.page(7)).isEqualTo(7);
    }

    @Test
    @DisplayName("MAX_SIZE 覆盖前端所有已知调用(size 最大 100)")
    void maxSize_coversFrontendUsage() {
        assertThat(PageParams.MAX_SIZE).isGreaterThanOrEqualTo(100);
    }
}

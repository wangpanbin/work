package com.cinema.modules.admin.dashboard;

import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Dashboard 4 张卡片查询(今日营收 / 订单数 / 已付 / 待付座位 / 已取消 / 已退款).
 *
 * <p>#3 收尾: 抽离 AdminDashboardService.summary() 内部的 6 个 fetcher,
 * 让 AdminDashboardService 退化为纯 orchestrator. 本类只关心"今日"的 4 张卡片,
 * 不涉及周维度/Top 维度(后者由 {@link DashboardTrendQuery} 负责).
 */
@Component
@RequiredArgsConstructor
public class DashboardHeadlineQuery {

    private final OrderMapper orderMapper;

    /** 今日已支付订单的营收合计 */
    public java.math.BigDecimal todayRevenue() {
        return orderMapper.sumRevenueToday();
    }

    /** 今日订单总数(全部状态) */
    public Integer todayOrders() {
        return orderMapper.countToday(null);
    }

    /** 今日已支付订单数 */
    public Integer todayPaid() {
        return orderMapper.countToday(OrderStatus.PAID.getCode());
    }

    /** 今日待支付订单的总座位数(null 表示今日无待付单, 归零) */
    public Integer todayPendingSeats() {
        Integer v = orderMapper.sumTodayPendingSeats();
        return v == null ? 0 : v;
    }

    /**
     * 今日<b>超时关单</b>数(仅延迟任务在支付截止后自动关掉的单)。
     *
     * <p>P3-2:原先这里调 {@code countTodayClosed(CANCELLED)},统计的是"今日全部已取消订单",
     * 把用户手动取消也算成超时关单,卡片口径与文案不符(实测手动取消 1 笔被计为「超时关单 1」)。
     */
    public Integer todayCancelled() {
        Integer v = orderMapper.countTodayTimeoutClosed();
        return v == null ? 0 : v;
    }

    /** 今日已退款订单数 */
    public Integer todayRefunded() {
        return orderMapper.countTodayClosed(OrderStatus.REFUNDED.getCode());
    }
}
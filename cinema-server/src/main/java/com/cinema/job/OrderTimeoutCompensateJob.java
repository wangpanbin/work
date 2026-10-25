package com.cinema.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.redis.SeatWrite;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.OrderCancelService;
import com.cinema.modules.order.service.core.OrderCore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 兜底补偿任务: 防 ZSet 消息丢失导致座位永久锁死
 * 每分钟扫描超时未支付订单, 交给幂等关单流程(与主链路重复执行也安全)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutCompensateJob {

    private final OrderMapper orderMapper;
    private final OrderCancelService orderCancelService;
    private final OrderCore orderCore;
    private final SeatLuaService seatLuaService;
    private final SeatEventPublisher seatEventPublisher;

    @Scheduled(fixedDelay = 60_000)
    public void compensate() {
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .lt(Order::getExpireAt, LocalDateTime.now())
                .last("LIMIT 200"));
        if (expired.isEmpty()) {
            return;
        }
        log.warn("[补偿任务] 发现 {} 个超时未关订单, 执行兜底关单", expired.size());
        expired.forEach(o -> orderCancelService.closeIfUnpaid(o.getOrderNo()));
    }

    /**
     * N1 补偿: 卡死退款单(REFUNDING 超 5min) → 已取消(降级, 不入账)。
     *
     * <p><b>降级时必须同时释放座位</b>: 退款中(REFUNDING)的订单, 座位是**已售**状态
     * (sold 位为 1)。原先这里只把订单状态改成「已取消」, 座位却还占着 ——
     * 订单已经死了, 座位却永远灰着不可再选, 而且再没有任何流程会碰它。
     * 这正是联调报告 F5 的形状: 「订单早取消了, 座位还锁着」。
     *
     * <p>释放走**退票通道**({@code refundSold}: 同清 sold + lock), 与正常退票一致;
     * 幂等, 重复执行不影响结果。
     *
     * <p><b>顺序: 先释放座位 → 再 CAS 改状态</b>(与 {@code OrderMapper#selectStuckRefunding}
     * 的注释一致)。反过来(CAS 先行)会开一个泄漏窗口: CAS 把订单改成「已取消」之后、
     * 座位还没释放时进程中断, 订单已终结就再没有流程会碰它 —— 座位永远占着, 正是 F5。
     *
     * <p><b>为什么不会二次释放掉别人的座位</b>(2026-10-24): 退票主流程是
     * 「CAS PAID→REFUNDING → 释放座位 → 调渠道退款 → CAS REFUNDING→REFUNDED」。
     * 若它在释放座位之后中途中断(进程被杀 / DB 写入异常), 订单会停在 REFUNDING,
     * 而座位**已经回到可选池** —— 可能已被他人买走。此时本任务再调一次退票释放通道,
     * 若无保护就会把**别人已付款的座位**清成可选 → 同一座位两张 PAID 订单 = 超卖。
     * 保护由 {@code refund_seat.lua} 里的**按订单幂等标记**提供: 退票主流程释放时
     * 原子写下 {@code cinema:order:seat-released:{orderNo}}, 本任务第二次调用会被标记
     * 挡下, 一个位都不碰。
     *
     * <p>状态推进用 CAS(3→2): 退票主流程可能刚好把单推到 REFUNDED,
     * 那时 CAS 会失败 —— 属于正常竞争, 不算失败(座位已被退票流程释放过)。
     */
    @Scheduled(fixedDelay = 5 * 60_000)
    public void compensateStuckRefund() {
        List<Order> stuck = orderMapper.selectStuckRefunding();
        if (stuck.isEmpty()) {
            return;
        }
        for (Order order : stuck) {
            List<Integer> seats = orderCore.seatIndexesOf(order);
            // 退票通道带 orderNo → 按订单幂等: 主流程已释放过就跳过, 不会误清他人座位
            seatLuaService.refundSold(new SeatWrite.Paid(order.getSessionId(), seats, order.getOrderNo()));
            int updated = orderMapper.casStuckRefundingAsCancelled(order.getOrderNo());
            if (updated == 0) {
                log.info("[补偿任务] orderNo={} 状态已被退票流程推进, 座位已释放", order.getOrderNo());
                continue;
            }
            seatEventPublisher.publishReleased(order.getSessionId(), seats);
            log.warn("[补偿任务] 卡死退款单降级为已取消 orderNo={} 释放座位 {}", order.getOrderNo(), seats);
        }
    }
}

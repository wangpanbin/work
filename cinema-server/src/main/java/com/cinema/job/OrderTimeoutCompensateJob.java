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
            seatLuaService.refundSold(new SeatWrite.Paid(order.getSessionId(), seats));
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

package com.cinema.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.OrderCancelService;
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
}

package com.cinema.infra.mq;

import com.cinema.infra.delay.DelayQueue;
import com.cinema.modules.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 超时关单主链路: 每 5 秒从延迟队列取出到期订单执行关单
 * 消费语义 at-least-once, closeIfUnpaid 以 DB CAS 保证幂等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutScanner {

    private final DelayQueue delayQueue;
    private final OrderService orderService;

    @Scheduled(fixedDelay = 5000)
    public void scan() {
        List<String> expired = delayQueue.pollExpired(200);
        if (expired.isEmpty()) {
            return;
        }
        log.info("[超时扫描] 到期订单 {} 个: {}", expired.size(), expired);
        expired.forEach(orderService::closeIfUnpaid);
    }
}

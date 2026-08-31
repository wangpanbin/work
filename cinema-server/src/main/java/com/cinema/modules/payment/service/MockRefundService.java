package com.cinema.modules.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 模拟退款 — 课程设计不接真实支付网关
 * <p>N1 实现: 写 refund_log 表即视为"退款成功", 失败抛 RuntimeException
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockRefundService {

    /**
     * @return refund_log.id, 由调用方写 status=0
     * @throws RuntimeException 模拟退款失败(本实现不抛,留 hook)
     */
    public void mockRefundChannel(String orderNo, BigDecimal amount) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("订单号为空,无法退款");
        }
        log.info("[模拟退款] 渠道处理中... orderNo={} amount={}", orderNo, amount);
        log.info("[模拟退款] 渠道返回成功 orderNo={} amount={}", orderNo, amount);
    }
}

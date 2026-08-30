package com.cinema.modules.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 模拟支付渠道: 课程设计不接真实网关, 模拟"支付中→成功"
 * 后续可替换为真实支付网关适配器
 */
@Slf4j
@Service
public class MockPaymentService {

    public void mockPayChannel(String orderNo) {
        log.info("[模拟支付] 渠道处理中... orderNo={}", orderNo);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("[模拟支付] 渠道返回成功 orderNo={}", orderNo);
    }
}

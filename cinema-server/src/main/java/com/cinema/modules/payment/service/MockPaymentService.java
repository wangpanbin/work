package com.cinema.modules.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 模拟支付渠道: 课程设计不接真实网关, 模拟"支付中→成功"
 * <p>Phase B-⑤ 优化: 删除 Thread.sleep(300). 该 sleep 在压测场景 C 中
 * 占据每个 pay 请求 P99 的 300ms 之上, 属于纯 demo 用阻塞, 不影响业务正确性.
 * <p>保留 mock 方法签名, 未来接真实支付网关适配器时直接替换实现.
 */
@Slf4j
@Service
public class MockPaymentService {

    public void mockPayChannel(String orderNo) {
        // 真实环境: 调用第三方支付网关 (同步返回或异步回调).
        // 本项目: 模拟校验, 仅做轻量入参校验 + 日志.
        // 业务方在 OrderService.pay 中已先做 CAS 防双花, 这里不再做幂等判断.
        if (!StringUtils.hasText(orderNo)) {
            log.warn("[模拟支付] 订单号为空, 拒绝处理");
            throw new IllegalArgumentException("订单号为空");
        }
        log.debug("[模拟支付] 渠道处理中... orderNo={}", orderNo);
        log.info("[模拟支付] 渠道返回成功 orderNo={}", orderNo);
    }
}

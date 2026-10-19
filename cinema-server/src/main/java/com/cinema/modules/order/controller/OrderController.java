package com.cinema.modules.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.annotation.IdempotencyKeys;
import com.cinema.common.annotation.Idempotent;
import com.cinema.common.annotation.RateLimit;
import com.cinema.common.context.UserContext;
import com.cinema.common.result.R;
import com.cinema.common.web.PageParams;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.service.OrderCancelService;
import com.cinema.modules.order.service.OrderLockService;
import com.cinema.modules.order.service.OrderPayService;
import com.cinema.modules.order.service.OrderQueryService;
import com.cinema.modules.order.service.TicketService;
import com.cinema.modules.order.vo.LockResultVO;
import com.cinema.modules.order.vo.OrderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderLockService orderLockService;
    private final OrderPayService orderPayService;
    private final OrderCancelService orderCancelService;
    private final OrderQueryService orderQueryService;
    private final TicketService ticketService;

    /** 锁座下单 */
    @PostMapping("/lock")
    // P0 修复: idempotent key 必须带 userId(否则 user1 和 user2 锁同一座位会撞同一个 key 互锁 3 秒);
    // ttl 从 3s 提到 8s 覆盖前端锁座 + 后端 IO + Lua + 事务的整段耗时, 防止用户浏览器卡一下就被受理成"重复点击"以外的提交
    @Idempotent(key = IdempotencyKeys.LOCK_BY_USER_SESSION_SEATS, ttl = 8,
            message = "锁座请求处理中,请勿重复点击")
    @RateLimit(key = IdempotencyKeys.RATE_LOCK_BY_USER_SESSION, permits = 5, window = 1)
    public R<LockResultVO> lock(@Valid @RequestBody LockSeatsDTO dto) {
        return R.ok(orderLockService.lockSeats(UserContext.userId(), dto));
    }

    /** 我的订单 */
    @GetMapping("/my")
    public R<Page<OrderVO>> my(@RequestParam(required = false) Integer status,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size) {
        return R.ok(orderQueryService.myOrders(UserContext.userId(), status,
                PageParams.page(page), PageParams.size(size)));
    }

    /** 订单详情 */
    @GetMapping("/{orderNo}")
    public R<OrderVO> detail(@PathVariable String orderNo) {
        return R.ok(orderQueryService.detail(orderNo, UserContext.userId()));
    }

    /** 模拟支付 */
    @PostMapping("/{orderNo}/pay")
    @Idempotent(key = IdempotencyKeys.PAY_BY_ORDER_NO, ttl = 5, message = "支付请求处理中,请勿重复点击")
    @RateLimit(key = IdempotencyKeys.RATE_PAY_BY_USER_ORDER, permits = 3, window = 1, unit = java.util.concurrent.TimeUnit.MINUTES)
    public R<Void> pay(@PathVariable String orderNo) {
        orderPayService.pay(orderNo, UserContext.userId());
        return R.ok();
    }

    /** 主动取消 */
    @PostMapping("/{orderNo}/cancel")
    @RateLimit(key = IdempotencyKeys.RATE_CANCEL_BY_USER_ORDER, permits = 3, window = 1, unit = java.util.concurrent.TimeUnit.MINUTES)
    public R<Void> cancel(@PathVariable String orderNo) {
        orderCancelService.cancel(orderNo, UserContext.userId());
        return R.ok();
    }

    /** N1 退票: PAID → REFUNDING → REFUNDED, 释放座位 + 写 refund_log */
    @PostMapping("/{orderNo}/refund")
    @Idempotent(key = IdempotencyKeys.REFUND_BY_ORDER_NO, ttl = 5, message = "退票请求处理中,请勿重复点击")
    @RateLimit(key = IdempotencyKeys.RATE_REFUND_BY_USER_ORDER, permits = 1, window = 1, unit = java.util.concurrent.TimeUnit.MINUTES)
    public R<Void> refund(@PathVariable String orderNo) {
        orderPayService.refund(orderNo, UserContext.userId());
        return R.ok();
    }

    /** N2 取电子票: 返回 payload + sig, 前端拼成二维码 */
    @GetMapping("/{orderNo}/ticket")
    public R<java.util.Map<String, String>> ticket(@PathVariable String orderNo) {
        return R.ok(ticketService.getTicket(orderNo, UserContext.userId()));
    }
}

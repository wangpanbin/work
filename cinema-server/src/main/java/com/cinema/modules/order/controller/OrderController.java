package com.cinema.modules.order.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.context.UserContext;
import com.cinema.common.result.R;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.service.OrderCancelService;
import com.cinema.modules.order.service.OrderLockService;
import com.cinema.modules.order.service.OrderPayService;
import com.cinema.modules.order.service.OrderQueryService;
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

    /** 锁座下单 */
    @PostMapping("/lock")
    public R<LockResultVO> lock(@Valid @RequestBody LockSeatsDTO dto) {
        return R.ok(orderLockService.lockSeats(UserContext.userId(), dto));
    }

    /** 我的订单 */
    @GetMapping("/my")
    public R<Page<OrderVO>> my(@RequestParam(required = false) Integer status,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size) {
        return R.ok(orderQueryService.myOrders(UserContext.userId(), status, page, size));
    }

    /** 订单详情 */
    @GetMapping("/{orderNo}")
    public R<OrderVO> detail(@PathVariable String orderNo) {
        return R.ok(orderQueryService.detail(orderNo, UserContext.userId()));
    }

    /** 模拟支付 */
    @PostMapping("/{orderNo}/pay")
    public R<Void> pay(@PathVariable String orderNo) {
        orderPayService.pay(orderNo, UserContext.userId());
        return R.ok();
    }

    /** 主动取消 */
    @PostMapping("/{orderNo}/cancel")
    public R<Void> cancel(@PathVariable String orderNo) {
        orderCancelService.cancel(orderNo, UserContext.userId());
        return R.ok();
    }
}

package com.cinema.modules.order.controller;

import com.cinema.common.result.R;
import com.cinema.modules.order.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 电子票验票端点 — 公开, 检票员扫码调用
 * <p>N2: 校验签名 + 一次性 verify
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @GetMapping("/verify")
    public R<TicketService.VerifyResult> verify(@RequestParam String payload,
                                                @RequestParam String sig) {
        return R.ok(ticketService.verify(payload, sig));
    }
}

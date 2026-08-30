package com.cinema.modules.seat.controller;

import com.cinema.common.context.UserContext;
import com.cinema.common.result.R;
import com.cinema.modules.seat.service.SeatService;
import com.cinema.modules.seat.vo.SeatMapVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;

    /** 座位图(公开可浏览, 登录后附带我锁的座位) */
    @GetMapping("/{sessionId}/seat-map")
    public R<SeatMapVO> seatMap(@PathVariable Long sessionId) {
        return R.ok(seatService.seatMap(sessionId, UserContext.userId()));
    }
}

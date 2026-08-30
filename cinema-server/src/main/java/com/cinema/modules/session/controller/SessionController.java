package com.cinema.modules.session.controller;

import com.cinema.common.result.R;
import com.cinema.modules.session.service.SessionService;
import com.cinema.modules.session.vo.SessionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /** 影片某日场次: GET /api/movies/{movieId}/sessions?date=2026-09-01 */
    @GetMapping("/movies/{movieId}/sessions")
    public R<List<SessionVO>> listByMovieAndDate(
            @PathVariable Long movieId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(sessionService.listByMovieAndDate(movieId, date == null ? LocalDate.now() : date));
    }

    /** 场次详情: GET /api/sessions/{sessionId} */
    @GetMapping("/sessions/{sessionId}")
    public R<SessionVO> detail(@PathVariable Long sessionId) {
        return R.ok(sessionService.detail(sessionId));
    }
}

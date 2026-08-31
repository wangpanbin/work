package com.cinema.modules.admin.controller;

import com.cinema.common.result.R;
import com.cinema.modules.admin.dto.DashboardSummaryVO;
import com.cinema.modules.admin.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O3 管理端经营看板 — 1 个端点返回所有指标
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    @GetMapping("/summary")
    public R<DashboardSummaryVO> summary() {
        return R.ok(dashboardService.summary());
    }
}

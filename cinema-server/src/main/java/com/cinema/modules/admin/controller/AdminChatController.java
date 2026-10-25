package com.cinema.modules.admin.controller;

import com.cinema.common.result.R;
import com.cinema.modules.chat.service.ChatAssistantLogService;
import com.cinema.modules.chat.vo.ChatAssistantStatsVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话助手用量与成本看板(v0.4.0 IT-11)。
 *
 * <p>对应战略计划 §7.5 的四项指标: 调用量 / 失败率 / P95 耗时 / 估算成本。
 *
 * <p><b>口径纪律</b>:成本是**估算值**,单价来自配置({@code cinema.chat.cost.*})且需人工维护 ——
 * 它不是财务口径,别拿它当真实账单。响应里同时带回红线指标
 * ({@code unconfirmedWrites} 必须恒为 0),让「助手没有发生越权写」是**可查证的**。
 *
 * <p>管理端接口,沿用既有的 {@code role === 1} 鉴权前缀(见 {@code WebConfig} 的
 * {@code AuthRequiredInterceptor} 覆盖路径)。
 */
@RestController
@RequestMapping("/api/admin/chat")
public class AdminChatController {

    private final ChatAssistantLogService logService;

    public AdminChatController(ChatAssistantLogService logService) {
        this.logService = logService;
    }

    /**
     * 近 N 天的助手用量统计。
     *
     * @param days 统计窗口(天),缺省 7;小于 1 时按 7 处理
     */
    @GetMapping("/stats")
    public R<ChatAssistantStatsVO> stats(@RequestParam(defaultValue = "7") int days) {
        return R.ok(logService.stats(days));
    }
}

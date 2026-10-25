package com.cinema.modules.chat.controller;

import com.cinema.common.result.R;
import com.cinema.modules.chat.agent.CinemaAssistant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读端点: 当前对话助手跑在哪个模型来源上。
 *
 * <p><b>为什么需要它</b>: 一键下单确认卡(AC-20 那条浏览器链路)在真实 LLM 下
 * 「出不出卡」取决于模型当天怎么发挥, 于是浏览器回归长期停在 SKIP。加了
 * {@code cinema.chat.provider=stub} 这个确定性夹具之后, E2E 需要知道自己面对的是
 * 哪一种 —— stub 模式下**必须**出卡(出不来就是回归), deepseek 模式下允许 SKIP。
 *
 * <p><b>它只暴露两个非敏感字段</b>: provider 名与「assistant Bean 是否就位」。
 * 不泄露 api-key、base-url、模型凭据, 也不写任何数据。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatStatusController {

    private final String provider;
    private final ObjectProvider<CinemaAssistant> assistantProvider;

    public ChatStatusController(@Value("${cinema.chat.provider:deepseek}") String provider,
                                ObjectProvider<CinemaAssistant> assistantProvider) {
        this.provider = provider;
        this.assistantProvider = assistantProvider;
    }

    /**
     * @return {@code {"provider":"deepseek"|"stub", "enabled":true|false}}
     *         {@code enabled=false} 表示没配 api-key 且不在 stub 模式 —— 此时
     *         {@code /api/chat/message} 会返 50000「对话功能未配置」。
     */
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", provider);
        data.put("enabled", assistantProvider.getIfAvailable() != null);
        return R.ok(data);
    }
}

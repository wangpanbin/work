package com.cinema.modules.chat.controller;

import com.cinema.common.annotation.RateLimit;
import com.cinema.common.result.R;
import com.cinema.modules.chat.dto.ChatRequestDTO;
import com.cinema.modules.chat.service.ChatAssistantService;
import com.cinema.modules.chat.vo.ChatResponseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * T5 — 对话入口 Controller(spec §6.1 + §6.3).
 *
 * <p>鉴权 + 限流:
 * <ul>
 *   <li>{@code /api/chat/**} 不在 {@code AuthRequiredInterceptor} 覆盖路径(spec §6.1)
 *       → 无 token 也可调,工具层 null userId 自动降级</li>
 *   <li>{@code @RateLimit} 限制匿名用户共用 10/min 桶(可接受 — spec Q3 决策);
 *       登录用户各自 10/min</li>
 * </ul>
 *
 * <p>SpEL key 用 {@code ?:} 短路 userId==null 时返回字面量 {@code 'anon'},
 * 避开 {@code T(...).userId()} 抛 NPE(1.x 对称行为,spec §5.4 (d) 提到).
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAssistantService chatAssistantService;

    @PostMapping("/message")
    @RateLimit(
            key = "T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'",
            permits = 10,
            window = 1,
            unit = TimeUnit.MINUTES,
            message = "对话请求过于频繁,请稍后再试")
    public R<ChatResponseVO> chatMessage(@RequestBody ChatRequestDTO dto) {
        Optional<ChatResponseVO> voOpt = chatAssistantService.chat(
                dto.getChatSessionId(), dto.getMessage());
        if (voOpt.isEmpty()) {
            // ChatConfig 在 api-key 为空时不注册 CinemaAssistant Bean(cycle 1/3)
            return R.fail(50000, "对话功能未配置");
        }
        return R.ok(voOpt.get());
    }
}
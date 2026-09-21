package com.cinema.modules.chat.controller;

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

/**
 * T1 cycle 3 — 对话入口 Controller(spec §6.1).
 *
 * <p>cycle 3 重构:从 cycle 2 直接用 {@code ObjectProvider<ChatModel>}
 * 切换到 {@link ChatAssistantService}(后者也是可选 Bean 路径 —
 * 服务内部用 {@code ObjectProvider<CinemaAssistant>}). 行为不变:
 * 无 key → 50000 + "对话功能未配置".
 *
 * <p>cycle 5 (T1) 接入 {@code @RateLimit} + {@code @Idempotent}.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAssistantService chatAssistantService;

    @PostMapping("/message")
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
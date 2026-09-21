package com.cinema.modules.chat.controller;

import com.cinema.common.result.R;
import com.cinema.modules.chat.dto.ChatRequestDTO;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T1 cycle 2 — 对话入口 Controller.
 *
 * <p>seam: 短路路径 — ChatModel Bean 不存在(由 cycle 1 ChatConfig 决定)时,
 * 直接返 {@code R.fail(50000, "对话功能未配置")},不抛 NPE,不返 HTTP 500.
 *
 * <p>用 {@link ObjectProvider} 而非 {@code @Autowired ChatModel} 注入 —
 * 后者在 bean 缺失时启动失败,前者允许 bean 缺失并通过 {@code getIfAvailable()}
 * 走 null 分支(spec §8 验收要求).
 *
 * <p>cycle 3 接入 ChatAssistantService,把 Assistant.chat() → reply/cards/followUps
 * 转换 + ChatMemory + 串行化串起来。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ObjectProvider<ChatModel> chatModelProvider;

    @PostMapping("/message")
    public R<?> chatMessage(@RequestBody ChatRequestDTO dto) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            log.info("[chat] ChatModel Bean 未注册, 走 50000 短路路径");
            return R.fail(50000, "对话功能未配置");
        }
        // cycle 3: 调 ChatAssistantService.chat(...) 把模型调用 / 工具调用链 / VO 转换串起来
        return R.ok();
    }
}
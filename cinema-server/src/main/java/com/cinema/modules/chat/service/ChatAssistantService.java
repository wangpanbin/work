package com.cinema.modules.chat.service;

import com.cinema.modules.chat.agent.CinemaAssistant;
import com.cinema.modules.chat.vo.ChatResponseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * T1 cycle 3 — 对话 orchestrator 雏形(spec §4.1).
 *
 * <p>cycle 3 阶段只实现两条路径:
 * <ul>
 *   <li>Bean 不存在 → {@code Optional.empty()}(Controller 翻译为 50000)</li>
 *   <li>Bean 存在 → 调 {@code assistant.chat(...)} 转 {@link ChatResponseVO}</li>
 * </ul>
 *
 * <p>不持有串行化锁(T3 ticket #10 接入) / 不持有 ChatMemory(T3 接入).
 *
 * <p>用 {@link ObjectProvider} 而非 {@code @Autowired} 注入 — Bean 不存在时启动不失败,
 * {@code getIfAvailable()} 返回 {@code null} 走空路径(spec §8 验收).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAssistantService {

    private final ObjectProvider<CinemaAssistant> assistantProvider;

    /**
     * 调 Assistant 并转为 ChatResponseVO.
     *
     * @return {@code Optional.empty()} 表示 Bean 未注册(对应 50000 短路路径),
     *         {@code Optional.of(vo)} 表示正常回复
     */
    public Optional<ChatResponseVO> chat(String chatSessionId, String message) {
        CinemaAssistant assistant = assistantProvider.getIfAvailable();
        if (assistant == null) {
            log.info("[chat] CinemaAssistant Bean 未注册, 走 50000 路径");
            return Optional.empty();
        }
        String reply = assistant.chat(chatSessionId, message);
        return Optional.of(ChatResponseVO.builder()
                .reply(reply)
                .cards(List.of())
                .followUps(List.of())
                .build());
    }
}
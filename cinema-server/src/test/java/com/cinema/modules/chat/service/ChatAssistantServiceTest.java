package com.cinema.modules.chat.service;

import com.cinema.modules.chat.agent.CinemaAssistant;
import com.cinema.modules.chat.vo.ChatResponseVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1 cycle 3 — ChatAssistantService orchestrator seam.
 *
 * <p>两条路径:
 * <ul>
 *   <li>Bean 不存在 → chat() 返 {@code Optional.empty()}(Controller 翻译为 50000)</li>
 *   <li>Bean 存在 → 调 {@code assistant.chat(chatSessionId, message)} 并转 VO</li>
 * </ul>
 *
 * <p>Mockito 风格(mock ObjectProvider + mock CinemaAssistant),沿用仓库既有测试风格.
 */
class ChatAssistantServiceTest {

    @Test
    @DisplayName("CinemaAssistant Bean 不存在时 chat() 返 Optional.empty()")
    void givenNoCinemaAssistantBean_whenChat_thenReturnsEmpty() {
        ObjectProvider<CinemaAssistant> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        ChatAssistantService service = new ChatAssistantService(emptyProvider);

        Optional<ChatResponseVO> result = service.chat("sid-1", "hi");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("CinemaAssistant Bean 存在时 chat() 调 chat(chatSessionId, message) 并转 VO(reply 透传,cards/followUps 空)")
    void givenCinemaAssistantBean_whenChat_thenReturnsVOWithReply() {
        CinemaAssistant assistant = mock(CinemaAssistant.class);
        when(assistant.chat("sid-1", "hi")).thenReturn("hello back");
        ObjectProvider<CinemaAssistant> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(assistant);
        ChatAssistantService service = new ChatAssistantService(provider);

        Optional<ChatResponseVO> result = service.chat("sid-1", "hi");

        assertThat(result).isPresent();
        assertThat(result.get().getReply()).isEqualTo("hello back");
        assertThat(result.get().getCards()).isEmpty();
        assertThat(result.get().getFollowUps()).isEmpty();
    }
}
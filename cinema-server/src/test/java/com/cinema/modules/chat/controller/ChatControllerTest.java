package com.cinema.modules.chat.controller;

import com.cinema.common.context.UserContext;
import com.cinema.modules.chat.service.ChatAssistantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 cycle 3 — ChatController 短路 seam(行为不变,只是 mock 对象从 ObjectProvider 变成 Service).
 *
 * <p>spec §6.2 + §8: api-key 为空时 ChatConfig 不注册 ChatModel/CinemaAssistant Bean,
 * ChatAssistantService.chat(...) 返回 {@code Optional.empty()},
 * ChatController 必须走 50000 短路路径,不抛 NPE,不返 HTTP 500.
 *
 * <p>测试策略沿用 cycle 2:MockMvc standaloneSetup + Mockito mock ChatAssistantService,
 * 不引入 @SpringBootTest(<100ms).
 */
class ChatControllerTest {

    private MockMvc mvc;

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("ChatAssistantService 返 Optional.empty() 时 POST /api/chat/message 返 R.fail(50000) + '对话功能未配置'")
    void givenChatAssistantServiceEmpty_whenChatMessage_thenReturnsR50000() throws Exception {
        ChatAssistantService mockService = mock(ChatAssistantService.class);
        when(mockService.chat(any(), any())).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(mockService)).build();

        String body = "{\"message\":\"hi\"}";

        mvc.perform(post("/api/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())  // 业务码 50000 时 HTTP 仍是 200,前端按 code 处理
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.msg").value("对话功能未配置"));
    }
}
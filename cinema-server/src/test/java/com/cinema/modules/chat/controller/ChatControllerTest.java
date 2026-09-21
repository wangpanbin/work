package com.cinema.modules.chat.controller;

import com.cinema.common.context.UserContext;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 cycle 2 — ChatController 短路 seam.
 *
 * <p>spec §6.2 + §8: api-key 为空时 ChatConfig 不注册 ChatModel Bean(cycle 1),
 * ChatController 必须走 50000 短路路径,不抛 NPE,不返回 500 HTTP.
 *
 * <p>测试策略:MockMvc standaloneSetup + Mockito mock ObjectProvider,
 * 模拟 ChatModel Bean 不存在的场景。沿用仓库 Mockito 风格,
 * 不引入 @SpringBootTest,跑得快(<100ms).
 */
class ChatControllerTest {

    private MockMvc mvc;

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    @DisplayName("ChatModel Bean 不存在时 POST /api/chat/message 返 R.fail(50000) + '对话功能未配置'")
    void givenNoChatModelBean_whenChatMessage_thenReturnsR50000() throws Exception {
        ObjectProvider<ChatModel> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(emptyProvider)).build();

        String body = "{\"message\":\"hi\"}";

        mvc.perform(post("/api/chat/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())  // 业务码 50000 时 HTTP 仍是 200,前端按 code 处理
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.msg").value("对话功能未配置"));
    }
}
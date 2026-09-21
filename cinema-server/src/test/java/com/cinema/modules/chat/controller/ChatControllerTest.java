package com.cinema.modules.chat.controller;

import com.cinema.common.annotation.RateLimit;
import com.cinema.common.context.UserContext;
import com.cinema.modules.chat.dto.ChatRequestDTO;
import com.cinema.modules.chat.service.ChatAssistantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T5 — ChatController 短路 seam + @RateLimit 注解配置断言.
 *
 * <p>覆盖(spec §9.1):
 * <ul>
 *   <li>ChatAssistantService 返 Optional.empty() → 50000(cycle 2/3 旧)</li>
 *   <li>反射断言 chatMessage() 上的 @RateLimit 注解配置正确(SpEL key / permits=10 / window=1min)</li>
 * </ul>
 *
 * <p>实际限流触发由 RateLimitAspectTest 覆盖(测 SpEL 解析 + 抛 BizException 42900).
 * ChatControllerTest 不引入 Spring AOP context(<100ms 单元测试).
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

    @Test
    @DisplayName("chatMessage 方法上有 @RateLimit 注解,SpEL key 与 10/min 桶配置正确")
    void chatMessage_hasRateLimitAnnotation() throws NoSuchMethodException {
        Method m = ChatController.class.getMethod("chatMessage", ChatRequestDTO.class);
        RateLimit annotation = m.getAnnotation(RateLimit.class);

        assertThat(annotation).as("@RateLimit 必须就位(spec §6.3 限流要求)").isNotNull();
        assertThat(annotation.key())
                .isEqualTo("T(com.cinema.common.context.UserContext).userId() ?: 'anon' + ':chat'");
        assertThat(annotation.permits()).isEqualTo(10);
        assertThat(annotation.window()).isEqualTo(1);
        assertThat(annotation.unit()).isEqualTo(TimeUnit.MINUTES);
    }
}
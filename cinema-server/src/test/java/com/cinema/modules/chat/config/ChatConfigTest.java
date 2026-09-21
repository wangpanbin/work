package com.cinema.modules.chat.config;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 cycle 1 — ChatConfig Bean 装配条件.
 *
 * <p>spec §9.1: api-key 为空时不注册 ChatModel Bean.
 *
 * <p>用 {@link ApplicationContextRunner} 跑单 ChatConfig,不启动完整
 * SpringBootTest (避免 DB / Redis 依赖,测试本身 <100ms).
 */
class ChatConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatConfig.class);

    @Test
    @DisplayName("api-key 为空字符串时 ChatModel Bean 不注册")
    void givenEmptyApiKey_whenContextStarts_thenChatModelBeanNotRegistered() {
        runner.withPropertyValues(
                "cinema.chat.api-key=",
                "cinema.chat.base-url=https://api.deepseek.com/v1",
                "cinema.chat.model-name=deepseek-chat"
        ).run(context -> {
            assertThat(context).doesNotHaveBean(ChatModel.class);
        });
    }

    @Test
    @DisplayName("api-key 完全缺失时 ChatModel Bean 不注册")
    void givenMissingApiKey_whenContextStarts_thenChatModelBeanNotRegistered() {
        runner.withPropertyValues(
                "cinema.chat.base-url=https://api.deepseek.com/v1",
                "cinema.chat.model-name=deepseek-chat"
                // cinema.chat.api-key 故意不设,模拟 application-dev.yml.example 没有 api-key 的情况
        ).run(context -> {
            assertThat(context).doesNotHaveBean(ChatModel.class);
        });
    }
}
package com.cinema.modules.chat.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * T1 cycle 1 — 对话助手 Bean 装配.
 *
 * <p>spec §8 + §6.2: api-key 为空或缺失时不注册 ChatModel Bean,让
 * {@code /api/chat/message} 走 {@code 50000 + "对话功能未配置"} 路径,保证
 * 无密钥环境下其他 12 个 Controller 仍可正常服务,{@code mvn test} 也无需密钥.
 *
 * <p>条件用 {@link ConditionalOnExpression} 而非 {@code @ConditionalOnProperty}:
 * 后者在 property 值为空字符串时仍视为"存在",会导致 ChatModel Bean 在
 * {@code cinema.chat.api-key=""} 时尝试创建时 NPE.
 *
 * <p>不用 langchain4j-spring-boot-starter (理由见 spec §3.2 — 仍是 beta,
 * 且会扫描所有 {@code @Component} 上的 {@code @Tool} 注入每一个 AI Service,
 * 存在意外暴露风险;另答辩解释成本高).
 */
@Configuration
public class ChatConfig {

    @Bean
    @ConditionalOnExpression("'${cinema.chat.api-key:}' != ''")
    public ChatModel chatModel(
            @Value("${cinema.chat.api-key}") String apiKey,
            @Value("${cinema.chat.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${cinema.chat.model-name:deepseek-chat}") String modelName,
            @Value("${cinema.chat.timeout-seconds:90}") long timeoutSeconds,
            @Value("${cinema.chat.max-retries:2}") int maxRetries
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(maxRetries)
                .build();
    }
}
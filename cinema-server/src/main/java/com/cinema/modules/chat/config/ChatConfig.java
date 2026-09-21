package com.cinema.modules.chat.config;

import com.cinema.modules.chat.agent.CinemaAssistant;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * T1 cycle 3 — 对话助手 Bean 装配(spec §4.5 + §8).
 *
 * <p>两个 Bean:
 * <ul>
 *   <li>{@code ChatModel} — @ConditionalOnExpression 控制 api-key 非空才注册(cycle 1)</li>
 *   <li>{@code CinemaAssistant} — @ConditionalOnBean(ChatModel.class) 控制 ChatModel 存在时才注册,
 *       AiServices.builder() 把 ChatModel 包装成代理实例,作为 {@code CinemaAssistant} 类型 Bean 注册</li>
 * </ul>
 *
 * <p>T2 ticket #9 接入 .tools(ChatTools),T3 ticket #10 接入 .chatMemoryProvider。
 *
 * <p>不用 langchain4j-spring-boot-starter (理由见 spec §3.2 — 仍是 beta,且会扫描
 * 所有 {@code @Component} 上的 {@code @Tool} 注入每一个 AI Service).
 */
@Slf4j
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

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public CinemaAssistant cinemaAssistant(ChatModel chatModel) {
        log.info("[chat] 注册 CinemaAssistant Bean (AiServices.builder)");
        return AiServices.builder(CinemaAssistant.class)
                .chatModel(chatModel)
                // .chatMemoryProvider(...)  // T3 ticket #10 接入
                // .tools(...)              // T2 ticket #9 接入 ChatTools
                .build();
    }
}
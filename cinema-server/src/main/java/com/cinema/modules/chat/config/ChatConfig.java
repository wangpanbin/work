package com.cinema.modules.chat.config;

import com.cinema.modules.chat.agent.CinemaAssistant;
import com.cinema.modules.chat.tools.ChatTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * T1 cycle 4 — 对话助手 Bean 装配 + 3 个 ErrorHandler(spec §4.5 + §5.4 + §8).
 *
 * <p>两个 Bean + 三个 ErrorHandler:
 * <ul>
 *   <li>{@code ChatModel} — @ConditionalOnExpression 控制 api-key 非空才注册(cycle 1)</li>
 *   <li>{@code CinemaAssistant} — @ConditionalOnBean(ChatModel.class) 控制 ChatModel 存在时才注册
 *       (cycle 3),AiServices.builder() 把 ChatModel 包装成代理实例,
 *       并挂上三个 ErrorHandler(spec §5.4 — 默认行为有 stack trace 泄漏 / 无效重试 / 幻觉工具名等坑,
 *       必须显式覆盖)</li>
 * </ul>
 *
 * <p>三个 ErrorHandler(spec §5.4 必须替换默认行为):
 * <ol>
 *   <li><b>toolArgumentsErrorHandler</b> — 工具参数错误:默认抛异常浪费 LLM 轮次,
 *       改为返 error.getMessage() 文本让模型知道哪里错</li>
 *   <li><b>toolExecutionErrorHandler</b> — 工具执行异常:默认把 stack trace 原文
 *       发给 LLM,会泄漏内部信息 / 凭据 / PII。改为返固定脱敏文本</li>
 *   <li><b>hallucinatedToolNameStrategy</b> — 模型调用不存在的工具名:
 *       默认抛异常,改为返 ToolExecutionResultMessage 让模型知道该工具不存在</li>
 * </ol>
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
    public CinemaAssistant cinemaAssistant(ChatModel chatModel, ChatTools chatTools) {
        log.info("[chat] 注册 CinemaAssistant Bean (AiServices.builder + 3 个 ErrorHandler + 7 个工具)");
        return AiServices.builder(CinemaAssistant.class)
                .chatModel(chatModel)
                // T2 ticket #9: 7 个只读工具(反射白名单 ChatToolsStructureTest 兜底)
                .tools(chatTools)
                // spec §5.4 (a) 工具参数错误: 不抛异常浪费 LLM 轮次
                .toolArgumentsErrorHandler((error, ctx) -> {
                    log.warn("[chat] 工具参数错误: {}", error.getMessage());
                    return ToolErrorHandlerResult.text(error.getMessage());
                })
                // spec §5.4 (b) 工具执行异常: 不暴露 stack trace / 内部路径 / 凭据
                .toolExecutionErrorHandler((error, ctx) -> {
                    log.error("[chat] 工具执行异常 (已脱敏): {}", error.getMessage());
                    return ToolErrorHandlerResult.text("查询失败,请稍后重试或换一种问法");
                })
                // spec §5.4 (c) 模型幻觉出不存在的工具名: 不抛异常,返 ToolExecutionResultMessage
                .hallucinatedToolNameStrategy(req -> {
                    log.warn("[chat] 模型调用不存在的工具: {}", req.name());
                    return ToolExecutionResultMessage.from(req,
                            "没有名为 " + req.name() + " 的工具,请从可用工具中选择");
                })
                // .chatMemoryProvider(...)  // T3 ticket #10 接入
                .build();
    }
}
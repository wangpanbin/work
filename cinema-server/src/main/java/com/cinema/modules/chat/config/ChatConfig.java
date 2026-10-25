package com.cinema.modules.chat.config;

import com.cinema.modules.chat.agent.CinemaAssistant;
import com.cinema.modules.chat.agent.StubCinemaAssistant;
import com.cinema.modules.chat.memory.ChatMemoryStore;
import com.cinema.modules.chat.tools.ChatTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T3 cycle 1 — 对话助手 Bean 装配(spec §4.5 + §8)。
 *
 * <p>三个 Bean:
 * <ul>
 *   <li>{@code ChatModel} — @ConditionalOnExpression 控制 api-key 非空才注册(cycle 1)</li>
 *   <li>{@code CinemaAssistant} — @ConditionalOnBean(ChatModel.class) 控制 ChatModel 存在时才注册,
 *       AiServices.builder() 把 ChatModel 包装成代理实例,并挂上 3 个 ErrorHandler +
 *       7 个工具 + ChatMemoryProvider(T3 接入串行化/记忆)</li>
 *   <li>{@code ChatMemoryStore} — 内存存储,按 chatSessionId 分桶,MessageWindowChatMemory
 *       窗口 10 条消息</li>
 *   <li>{@code Clock} — 系统 UTC 默认,T3 测试可覆盖</li>
 * </ul>
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

    /**
     * ChatMemoryStore 默认实现:ConcurrentHashMap + MessageWindowChatMemory 窗口 10 条
     * (spec §4.4 窗口大小下限,允许完整工具调用链组不被截断)。
     */
    @Bean
    public ChatMemoryStore chatMemoryStore() {
        ConcurrentHashMap<String, ChatMemory> map = new ConcurrentHashMap<>();
        return new ChatMemoryStore() {
            @Override
            public ChatMemory get(String chatSessionId) {
                return map.computeIfAbsent(chatSessionId,
                        k -> MessageWindowChatMemory.builder()
                                .id(chatSessionId)
                                .maxMessages(10)
                                .build());
            }

            @Override
            public void evict(String chatSessionId) {
                ChatMemory m = map.remove(chatSessionId);
                if (m != null) m.clear();
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    @ConditionalOnProperty(name = "cinema.chat.provider", havingValue = "deepseek", matchIfMissing = true)
    public CinemaAssistant cinemaAssistant(ChatModel chatModel, ChatTools chatTools, ChatMemoryStore memoryStore) {
        log.info("[chat] 注册 CinemaAssistant Bean (AiServices.builder + 3 个 ErrorHandler + 7 个工具 + ChatMemoryProvider)");
        return AiServices.builder(CinemaAssistant.class)
                .chatModel(chatModel)
                // T3: ChatMemoryProvider(spec §4.4 + Q1 决策:按 id 分桶,30min evict)
                .chatMemoryProvider(memoryId -> memoryStore.get((String) memoryId))
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
                .build();
    }

    /**
     * 假 LLM Bean —— {@code cinema.chat.provider=stub} 时取代真实模型。
     *
     * <p>只替换「模型决策」这一步, 卡片解析 / 提案装配 / 座位重读 / 前端渲染全是生产代码。
     * 目的是让「一键下单确认卡」那条浏览器链路能**确定性**回归, 而不是看 LLM 当天心情。
     *
     * <p>它<b>不依赖 {@link ChatModel}</b> —— 所以没配 api-key 也能起, E2E 夹具可以完全离线跑。
     */
    @Bean
    @ConditionalOnProperty(name = "cinema.chat.provider", havingValue = "stub")
    public CinemaAssistant stubCinemaAssistant(ChatTools chatTools) {
        log.info("[chat] 注册 CinemaAssistant Bean (stub provider —— 确定性回复, 不调用真实 LLM)");
        return new StubCinemaAssistant(chatTools);
    }
}
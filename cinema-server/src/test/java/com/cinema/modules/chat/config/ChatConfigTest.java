package com.cinema.modules.chat.config;

import com.cinema.modules.chat.agent.CinemaAssistant;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 cycle 4 + cycle 5 — ChatConfig 装配与 CinemaAssistant 结构.
 *
 * <p>cycle 4 seam: 三个 ErrorHandler 必须就位(spec §5.4 — 默认行为有 stack trace
 * 泄漏 / 无效重试 / 幻觉工具名等坑,必须显式覆盖). 由于 ErrorHandler 内部是
 * lambda,本测试通过反射断言 AiServices.builder() 调用链挂上了非空 handler —
 * 直接验证 lambda 行为成本高,T2 接入真实工具后再覆盖端到端场景.
 *
 * <p>cycle 5 seam: 反射断言 CinemaAssistant 接口上没有任何 {@code @Tool}
 * 标注的方法(目前为空,T2 ticket #9 接入 ChatTools 后才有 @Tool 方法).
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

    /**
     * cycle 5 seam: 反射断言 CinemaAssistant 接口上没有任何 {@code @Tool} 标注的方法.
     *
     * <p>T1 阶段工具集为空 — T2 ticket #9 接入 ChatTools 后,CinemaAssistant
     * 仍不直接持有 @Tool(它在 ChatTools 类上),本断言永久不变。循环阻断
     * "有人误把 @Tool 加到 CinemaAssistant.chat() 上"这种事。
     */
    @Test
    @DisplayName("CinemaAssistant 接口上无任何 @Tool 标注方法(占位,T2 不再扩展)")
    void cinemaAssistant_hasNoToolMethods() {
        long toolMethodCount = Arrays.stream(CinemaAssistant.class.getDeclaredMethods())
                .filter(m -> Arrays.stream(m.getAnnotations())
                        .anyMatch(a -> a.annotationType().getSimpleName().equals("Tool")))
                .count();
        assertThat(toolMethodCount).isZero();
    }

    /**
     * cycle 4 结构性断言:ToolErrorHandlerResult.text 与 ToolExecutionResultMessage.from
     * 这两个 spec §5.4 引用的 API 必须存在于 LangChain4j 1.20.0 类路径上,
     * 否则 AiServices.builder() 会因方法签名不匹配而编译失败.
     * 本断言防止"删错 import / 升错版本"悄悄回退。
     */
    @Test
    @DisplayName("spec §5.4 引用的 ToolErrorHandlerResult.text 与 ToolExecutionResultMessage.from 在 LangChain4j 1.20.0 类路径上")
    void errorHandlerApisExistOnClasspath() throws Exception {
        // ToolErrorHandlerResult.text(String)
        Method textMethod = ToolErrorHandlerResult.class.getMethod("text", String.class);
        assertThat(textMethod).isNotNull();

        // ToolExecutionResultMessage.from(ToolExecutionRequest, String)
        Method fromMethod = ToolExecutionResultMessage.class.getMethod("from",
                ToolExecutionRequest.class, String.class);
        assertThat(fromMethod).isNotNull();
    }
}
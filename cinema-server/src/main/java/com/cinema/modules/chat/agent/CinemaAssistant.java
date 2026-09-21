package com.cinema.modules.chat.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * T1 cycle 3 — AiService 接口(spec §4.5).
 *
 * <p>AiServices.builder(CinemaAssistant.class) 用此接口生成代理实例,作为
 * {@code CinemaAssistant} 类型 Bean 注册到 Spring context.
 *
 * <p>T1 cycle 3 阶段还没接 ChatTools(工具集在 T2 ticket #9 接入)、
 * 还没接 ChatMemoryProvider(T3 ticket #10 接入串行化 + 记忆).
 *
 * <p>{@code @MemoryId} 让 LangChain4j 按 {@code chatSessionId} 自动路由
 * ChatMemory(spec §4.4 作用域按 chatSessionId 分桶).
 */
public interface CinemaAssistant {

    @SystemMessage("""
            你是影院对话助手,只读不写 — 只能调用只读工具(查影片、查场次、查余座、查订单),
            不能锁座、不能支付、不能退票、不能建单(ADR-0002).
            若用户表达想锁座/支付,只返回自然语言回复或行动卡片结构化建议,
            让用户在前端既有流程里确认后执行.
            """)
    String chat(@MemoryId String chatSessionId, @UserMessage String userMessage);
}
package com.cinema.modules.chat.memory;

import dev.langchain4j.memory.ChatMemory;

/**
 * T3 cycle 1 — 聊天会话内存存储(spec §4.3 + §4.4).
 *
 * <p>独立接口(不依赖 LangChain4j 的 {@code ChatMemoryProvider} 接口 — 后者只有
 * {@code get(memoryId)} 没有 evict 语义).ChatConfig 持有此 Bean,在 cinemaAssistant
 * Bean 装配时包装为 LangChain4j 的 {@code ChatMemoryProvider},传递给的 builder.
 *
 * <p>spec Q6 决策:evict 与串行化锁必须同生命周期清理,否则串行化重入会拿到"空 memory"
 * 破坏语义。
 */
public interface ChatMemoryStore {

    /**
     * 获取(必要时创建)chatSessionId 对应的 ChatMemory 实例.
     */
    ChatMemory get(String chatSessionId);

    /**
     * 显式 evict:同步移除 memory + 清空消息,与 ChatAssistantService 持有的
     * serializationLocks / lastAccessAt 协调(由 ChatAssistantService.evict 触发).
     */
    void evict(String chatSessionId);
}
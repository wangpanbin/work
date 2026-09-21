package com.cinema.modules.chat.service;

import com.cinema.modules.chat.agent.CinemaAssistant;
import com.cinema.modules.chat.memory.ChatMemoryStore;
import com.cinema.modules.chat.vo.ChatResponseVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T3 cycle 1 — 对话 orchestrator 完整版(spec §4.1 + §4.3 + §4.4 + Q1/Q6).
 *
 * <p>新增相对 cycle 3:
 * <ul>
 *   <li>{@code serializationLocks} — ConcurrentHashMap<String, ReentrantLock>,按 chatSessionId 串行化
 *       (防止并发损坏 ChatMemory,LangChain4j 官方警告)</li>
 *   <li>{@code lastAccessAt} — ConcurrentHashMap<String, Long>,30min idle 检测用</li>
 *   <li>{@code Clock} — 可注入,默认 {@code Clock.systemUTC()},测试用 {@code Clock.fixed(...)}</li>
 *   <li>{@code evict(id)} — 同步清 serializationLocks + memoryStore + lastAccessAt(spec Q6)</li>
 *   <li>{@code evictIfIdle(id, now)} — 入口检查 idle 并 evict(spec Q1:30min)</li>
 *   <li>{@code evictIdleSessions()} — @Scheduled(fixedDelay=60s) 后台扫(spec §4.4)</li>
 * </ul>
 */
@Slf4j
@Service
public class ChatAssistantService {

    private static final long IDLE_TIMEOUT_MILLIS = 30 * 60 * 1000L; // 30 min

    private final ObjectProvider<CinemaAssistant> assistantProvider;
    private final ChatMemoryStore memoryStore;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> serializationLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastAccessAt = new ConcurrentHashMap<>();

    @Autowired
    public ChatAssistantService(ObjectProvider<CinemaAssistant> assistantProvider,
                                ChatMemoryStore memoryStore) {
        this(assistantProvider, memoryStore, Clock.systemUTC());
    }

    /** 测试用三参数构造器,允许注入 fixed Clock + mock memoryStore. */
    public ChatAssistantService(ObjectProvider<CinemaAssistant> assistantProvider,
                                ChatMemoryStore memoryStore,
                                Clock clock) {
        this.assistantProvider = assistantProvider;
        this.memoryStore = memoryStore;
        this.clock = clock;
    }

    /**
     * 调 Assistant 并转为 ChatResponseVO(spec §4.3).
     *
     * @return {@code Optional.empty()} 表示 Bean 未注册(对应 50000 短路路径),
     *         {@code Optional.of(vo)} 表示正常回复
     */
    public Optional<ChatResponseVO> chat(String chatSessionId, String message) {
        CinemaAssistant assistant = assistantProvider.getIfAvailable();
        if (assistant == null) {
            log.info("[chat] CinemaAssistant Bean 未注册, 走 50000 路径");
            return Optional.empty();
        }

        // 1. 入口检查 idle 并 evict(spec Q1:空闲 30min 才新建桶)
        evictIfIdle(chatSessionId);

        // 2. 拿串行化锁(同 chatSessionId 串行,不同并行 — LangChain4j 官方警告)
        ReentrantLock lock = serializationLocks.computeIfAbsent(chatSessionId, k -> new ReentrantLock());
        lock.lock();
        try {
            String reply = assistant.chat(chatSessionId, message);
            lastAccessAt.put(chatSessionId, clock.millis());
            // T9: 解析 LLM 在 reply 末尾 fence 的 ```json-cards ... ``` 块,
            // 提取 cards + followUps + 剥离 fence 后的 reply(spec §6.1 + #16 acceptance).
            // 解析失败静默回退到 cards=List.of(),不抛(spec §5.4 容错路径).
            ReplyCardParser.ParseResult parsed = ReplyCardParser.parse(reply);
            return Optional.of(ChatResponseVO.builder()
                    .reply(parsed.strippedReply())
                    .cards(parsed.cards())
                    .followUps(parsed.followUps())
                    .build());
        } finally {
            lock.unlock();
        }
    }

    /** spec §4.3 + Q6: 串行化 map 与 ChatMemory 必须同生命周期清理 */
    public void evict(String chatSessionId) {
        log.info("[chat] evict chatSessionId={}", chatSessionId);
        serializationLocks.remove(chatSessionId);
        memoryStore.evict(chatSessionId);
        lastAccessAt.remove(chatSessionId);
    }

    /** spec Q1: 空闲 30min 自动 evict */
    private void evictIfIdle(String chatSessionId) {
        Long last = lastAccessAt.get(chatSessionId);
        if (last == null || clock.millis() - last > IDLE_TIMEOUT_MILLIS) {
            evict(chatSessionId);
        }
    }

    /** spec §4.4 后台扫: fixedDelay=60s,清理 30min 未访问的桶 */
    @Scheduled(fixedDelay = 60_000)
    public void evictIdleSessions() {
        long cutoff = clock.millis() - IDLE_TIMEOUT_MILLIS;
        List<String> expired = new ArrayList<>();
        lastAccessAt.forEach((sid, last) -> {
            if (last < cutoff) expired.add(sid);
        });
        if (expired.isEmpty()) return;
        log.info("[chat] evictIdleSessions 清理 {} 个过期会话", expired.size());
        for (String sid : expired) {
            evict(sid);
        }
    }
}
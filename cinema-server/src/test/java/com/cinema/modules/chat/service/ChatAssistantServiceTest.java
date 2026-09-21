package com.cinema.modules.chat.service;

import com.cinema.modules.chat.agent.CinemaAssistant;
import com.cinema.modules.chat.memory.ChatMemoryStore;
import com.cinema.modules.chat.vo.ChatResponseVO;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T3 cycle 1 — ChatAssistantService 完整版 seam.
 *
 * <p>覆盖:
 * <ul>
 *   <li>Bean 不存在 → Optional.empty()(cycle 3 旧)</li>
 *   <li>Bean 存在 → chat() 转 VO(cycle 3 旧)</li>
 *   <li><b>同 chatSessionId 并发被串行化</b>(spec §4.3 + T3 acceptance:CountDownLatch 互斥)</li>
 *   <li><b>不同 chatSessionId 不互锁</b>(可并行 — 验收"串行化按 memoryId 粒度")</li>
 *   <li><b>空闲 30min 后 evictIfIdle 触发</b>(spec §4.4 + Q1)</li>
 *   <li><b>evict(id) 后,同 id 立即可新会话</b>(spec §4.3 + Q6 同步清)</li>
 *   <li><b>evictIdleSessions() 后台扫清桶</b>(spec §4.4)</li>
 * </ul>
 *
 * <p>Clock 注入 {@link Clock#fixed} 让时间可控。
 */
class ChatAssistantServiceTest {

    private ObjectProvider<CinemaAssistant> provider;
    private CinemaAssistant assistant;
    private ChatMemoryStore store;
    private Clock clock;
    private ChatAssistantService service;

    @BeforeEach
    void setUp() {
        provider = mock(ObjectProvider.class);
        assistant = mock(CinemaAssistant.class);
        store = mock(ChatMemoryStore.class);
        // 默认给一个 ChatMemory 实例(实际是空)
        when(store.get(anyString())).thenAnswer(inv -> MessageWindowChatMemory.builder()
                .id(inv.getArgument(0))
                .maxMessages(10)
                .build());
        clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneId.of("UTC"));
        when(provider.getIfAvailable()).thenReturn(assistant);
        when(assistant.chat(anyString(), anyString())).thenReturn("ok");
        service = new ChatAssistantService(provider, store, clock);
    }

    // ============ cycle 3 旧测试 ============

    @Test
    @DisplayName("CinemaAssistant Bean 不存在时 chat() 返 Optional.empty()")
    void givenNoCinemaAssistantBean_whenChat_thenReturnsEmpty() {
        when(provider.getIfAvailable()).thenReturn(null);
        Optional<ChatResponseVO> result = service.chat("sid-1", "hi");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("CinemaAssistant Bean 存在时 chat() 调 chat(chatSessionId, message) 并转 VO(reply 透传,cards/followUps 空)")
    void givenCinemaAssistantBean_whenChat_thenReturnsVOWithReply() {
        when(assistant.chat("sid-1", "hi")).thenReturn("hello back");
        Optional<ChatResponseVO> result = service.chat("sid-1", "hi");
        assertThat(result).isPresent();
        assertThat(result.get().getReply()).isEqualTo("hello back");
        assertThat(result.get().getCards()).isEmpty();
        assertThat(result.get().getFollowUps()).isEmpty();
    }

    // ============ T3 新增测试 ============

    @Test
    @DisplayName("同 chatSessionId 并发调用被串行化(AtomicInteger 记录最大并发,断言 == 1)")
    void givenSameChatSessionId_concurrentCalls_areSerialized() throws Exception {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        when(assistant.chat(anyString(), anyString())).thenAnswer(inv -> {
            int now = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
            Thread.sleep(50);
            concurrentCount.decrementAndGet();
            return "ok";
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> f1 = executor.submit(() -> service.chat("sid-1", "msg-1"));
        Future<?> f2 = executor.submit(() -> service.chat("sid-1", "msg-2"));
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
        executor.shutdown();

        // spec §4.3 串行化保证互斥,最大并发 == 1
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 chatSessionId 并发调用不互锁(并行,最大并发 == 2)")
    void givenDifferentChatSessionIds_concurrentCalls_canRunInParallel() throws Exception {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        when(assistant.chat(anyString(), anyString())).thenAnswer(inv -> {
            int now = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, now));
            Thread.sleep(50);
            concurrentCount.decrementAndGet();
            return "ok";
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> f1 = executor.submit(() -> service.chat("sid-1", "msg-1"));
        Future<?> f2 = executor.submit(() -> service.chat("sid-2", "msg-2"));
        f1.get(2, TimeUnit.SECONDS);
        f2.get(2, TimeUnit.SECONDS);
        executor.shutdown();

        // 不同 chatSessionId 各自独立锁,应能并行
        assertThat(maxConcurrent.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("空闲 30min 后再 chat() 触发 evictIfIdle → 旧桶清,ChatMemory.evict 被调")
    void givenIdleOver30Min_nextChatEvictsOldSession() {
        // 第一次 chat 让 lastAccessAt=10:00
        service.chat("sid-1", "hi");
        // 模拟时间过去 31min
        Instant later = Instant.parse("2026-09-21T10:31:00Z");
        clock = Clock.fixed(later, ZoneId.of("UTC"));
        service = new ChatAssistantService(provider, store, clock);

        // 再 chat 应触发 evictIfIdle → evict → memoryStore.evict 被调
        service.chat("sid-1", "hi again");

        // mockito 验证 store.evict("sid-1") 被调用过至少一次
        org.mockito.Mockito.verify(store, org.mockito.Mockito.atLeastOnce()).evict("sid-1");
    }

    @Test
    @DisplayName("evict(id) 后,同 id 立即可新会话(旧 memory 清空)")
    void givenEvict_thenSameIdImmediatelyUsable() {
        // 第一次 chat
        service.chat("sid-1", "hi");
        // 显式 evict
        service.evict("sid-1");
        // 再 chat — Assistant 应该仍被调(因为 memory 是新桶)
        service.chat("sid-1", "hi again");

        // assistant.chat 应被调 2 次,分别对应两个不同的 message
        org.mockito.Mockito.verify(assistant, org.mockito.Mockito.times(1)).chat("sid-1", "hi");
        org.mockito.Mockito.verify(assistant, org.mockito.Mockito.times(1)).chat("sid-1", "hi again");
    }

    @Test
    @DisplayName("evictIdleSessions() 后台扫清 idle 桶(30min 阈值)")
    void evictIdleSessions_removesExpiredBuckets() {
        // 第一次 chat at 10:00
        service.chat("sid-1", "hi");
        service.chat("sid-2", "hi");

        // 时间推进 31min,sid-1 和 sid-2 都过期
        clock = Clock.fixed(Instant.parse("2026-09-21T10:31:00Z"), ZoneId.of("UTC"));
        service = new ChatAssistantService(provider, store, clock);

        // 触发后台扫
        service.evictIdleSessions();

        // memoryStore.evict 应该被调过(2 次,一个桶一次)
        org.mockito.Mockito.verify(store, org.mockito.Mockito.atLeast(2)).evict(anyString());
    }
}
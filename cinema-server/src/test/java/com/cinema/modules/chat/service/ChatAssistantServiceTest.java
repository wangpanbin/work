package com.cinema.modules.chat.service;

import com.cinema.modules.chat.agent.CinemaAssistant;
import com.cinema.modules.chat.memory.ChatMemoryStore;
import com.cinema.modules.chat.vo.ChatResponseVO;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(assistant.chat(anyString(), anyString(), anyString())).thenReturn("ok");
        service = new ChatAssistantService(provider, store, clock);
    }

    // ============ P2-4:系统提示词注入当前日期 ============

    /**
     * P2-4 回归:LLM 不知道"今天"是哪天,会把过期场次当"明天"推荐。
     * 提示词必须拿到今天/明天/后天三个绝对日期。
     */
    @Test
    @DisplayName("P2-4:currentDateContext() 含今天/明天/后天绝对日期,LLM 才能换算相对日期")
    void currentDateContext_containsTodayTomorrowDayAfter() {
        String ctx = service.currentDateContext();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        assertThat(ctx)
                .contains("今天是 " + today)
                .contains("明天是 " + today.plusDays(1))
                .contains("后天是 " + today.plusDays(2));
    }

    /** 时区陷阱:注入的 Clock 是 UTC,若拿它算"今天",北京时间凌晨 0~8 点会算成昨天 */
    @Test
    @DisplayName("P2-4:currentDateContext() 按 Asia/Shanghai 而非注入的 UTC Clock 计算")
    void currentDateContext_usesCinemaZoneNotInjectedUtcClock() {
        // 测试注入的 fixed clock 是 2026-09-21T10:00:00Z
        String ctx = service.currentDateContext();
        LocalDate shanghaiToday = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate utcToday = LocalDate.now(ZoneId.of("UTC"));

        // 断言用的是影院时区的今天(两者在多数时刻相同,关键是实现没绑到 UTC Clock)
        assertThat(ctx).contains("今天是 " + shanghaiToday);
        if (!shanghaiToday.equals(utcToday)) {
            // 跨日窗口:两种时区结论不同时,上下文必须站在影院时区这边
            assertThat(ctx).doesNotContain("今天是 " + utcToday);
        }
    }

    @Test
    @DisplayName("P2-4:chat() 把日期上下文作为第 2 个参数传给 Assistant")
    void chat_passesDateContextToAssistant() {
        service.chat("sid-1", "明天有什么场次");

        ArgumentCaptor<String> dateCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(assistant).chat(eq("sid-1"), dateCaptor.capture(), eq("明天有什么场次"));
        assertThat(dateCaptor.getValue()).contains("今天是").contains("明天是");
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
        when(assistant.chat(eq("sid-1"), anyString(), eq("hi"))).thenReturn("hello back");
        Optional<ChatResponseVO> result = service.chat("sid-1", "hi");
        assertThat(result).isPresent();
        assertThat(result.get().getReply()).isEqualTo("hello back");
        assertThat(result.get().getCards()).isEmpty();
        assertThat(result.get().getFollowUps()).isEmpty();
    }

    // ============ T9 新增契约(spec §6.1 + #16 acceptance)============

    @Test
    @DisplayName("LLM reply 含 ```json-cards``` fence → chat() 提取 cards + followUps + 剥离 fence")
    void givenLLMReplyWithCardFence_whenChat_thenExtractsCardsAndFollowUps() {
        String replyWithFence = "今晚 8 点《流浪地球》19 号厅还有 42 座。\n\n"
                + "```json-cards\n"
                + "{\"cards\":[{\"type\":\"SEAT_SUGGESTION\",\"sessionId\":\"1001\","
                + "\"movieTitle\":\"流浪地球\",\"hallName\":\"19 号厅\","
                + "\"startTime\":\"2026-09-21 20:00:00\",\"price\":45.00,"
                + "\"seatIndexes\":[52,53],\"seatDesc\":\"5排4座、5排5座\","
                + "\"totalAmount\":90.00,\"actionLabel\":\"去选座确认\"}],"
                + "\"followUps\":[\"换一个场次\",\"只看 VIP 厅\"]}\n"
                + "```";
        when(assistant.chat(eq("sid-t9"), anyString(), eq("推荐座位"))).thenReturn(replyWithFence);

        Optional<ChatResponseVO> result = service.chat("sid-t9", "推荐座位");
        assertThat(result).isPresent();
        ChatResponseVO vo = result.get();
        // 1) reply 剥离 fence
        assertThat(vo.getReply()).doesNotContain("```json-cards");
        assertThat(vo.getReply()).doesNotContain("SEAT_SUGGESTION");
        assertThat(vo.getReply()).startsWith("今晚 8 点");
        // 2) cards 提取 1 张
        assertThat(vo.getCards()).hasSize(1);
        assertThat(vo.getCards().get(0).getSessionId()).isEqualTo("1001");
        assertThat(vo.getCards().get(0).getSeatIndexes()).containsExactly(52, 53);
        // 3) followUps 提取 2 个
        assertThat(vo.getFollowUps()).containsExactly("换一个场次", "只看 VIP 厅");
    }

    @Test
    @DisplayName("LLM reply 含坏 JSON fence → chat() 静默回退 cards=[], fence 仍剥离")
    void givenLLMReplyWithBadJsonFence_whenChat_thenSilentFallback() {
        String replyBad = "回答\n```json-cards\n{not valid}\n```";
        when(assistant.chat(eq("sid-t9-bad"), anyString(), eq("x"))).thenReturn(replyBad);

        Optional<ChatResponseVO> result = service.chat("sid-t9-bad", "x");
        assertThat(result).isPresent();
        assertThat(result.get().getCards()).isEmpty();
        assertThat(result.get().getFollowUps()).isEmpty();
        // fence 仍剥离(UX 优先,不让用户看到 broken 块)
        assertThat(result.get().getReply()).doesNotContain("```json-cards");
        assertThat(result.get().getReply()).doesNotContain("not valid");
    }

    @Test
    @DisplayName("LLM reply 无 fence → chat() 走原路径(cards=[],reply 原样)")
    void givenLLMReplyWithoutFence_whenChat_thenOriginalReplyUnchanged() {
        String plain = "今晚 8 点有 3 场《流浪地球》。";
        when(assistant.chat(eq("sid-t9-plain"), anyString(), eq("hi"))).thenReturn(plain);

        Optional<ChatResponseVO> result = service.chat("sid-t9-plain", "hi");
        assertThat(result).isPresent();
        assertThat(result.get().getReply()).isEqualTo(plain);
        assertThat(result.get().getCards()).isEmpty();
    }

    // ============ T3 新增测试 ============

    @Test
    @DisplayName("同 chatSessionId 并发调用被串行化(AtomicInteger 记录最大并发,断言 == 1)")
    void givenSameChatSessionId_concurrentCalls_areSerialized() throws Exception {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        when(assistant.chat(anyString(), anyString(), anyString())).thenAnswer(inv -> {
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
        when(assistant.chat(anyString(), anyString(), anyString())).thenAnswer(inv -> {
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

    /**
     * 竞态回归 — <b>确定性编排</b>:第二次 chat 由**主线程**发起,不靠"再开一个线程 + sleep"
     * 去猜它的调度时机(那版在机器负载高时会误报/漏报,实测同机跑 5 次结果不一致)。
     *
     * <p><b>原缺陷</b>:{@code evict()} 无条件执行 {@code serializationLocks.remove(sid)}。
     * 当 T1 已经持有该会话的锁并停在 assistant 里时,任何一次 evict 都会把 T1 <b>正在使用</b>的锁
     * 从 map 里删掉;随后进来的调用 {@code computeIfAbsent} 到一把<b>全新的锁</b>,
     * 于是两个线程同时进入同一个 ChatMemory —— 串行化直接失效。
     *
     * <p><b>编排</b>:
     * <ol>
     *   <li>后台线程跑 T1,卡在 assistant(mock 里的 latch),确定已持有锁</li>
     *   <li>主线程调 {@code evict(sid)} —— 缺陷版在此把 T1 的锁摘掉</li>
     *   <li>主线程调 {@code chat(sid)}:<b>缺陷版立刻进入 assistant</b>(拿到新锁),
     *       修复版则阻塞在同一把锁上</li>
     *   <li>断言最大并发 == 1</li>
     * </ol>
     */
    @Test
    @DisplayName("竞态回归:持锁期间发生 evict 不得让后续调用拿到另一把锁(串行化必须有效)")
    void evictWhileLocked_doesNotBreakSerialization() throws Exception {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch t1Inside = new CountDownLatch(1);
        CountDownLatch releaseT1 = new CountDownLatch(1);
        try {
            when(assistant.chat(anyString(), anyString(), anyString())).thenAnswer(inv -> {
                maxConcurrent.updateAndGet(prev -> Math.max(prev, concurrentCount.incrementAndGet()));
                t1Inside.countDown();
                // 等主线程走完 evict + 第二次 chat;超时只是兜底,正常路径由主线程放行
                releaseT1.await(10, TimeUnit.SECONDS);
                concurrentCount.decrementAndGet();
                return "ok";
            });

            // 必须是 2 线程:单线程池里 T1 卡在 mock 上时 T2 根本排不上队,
            // 会让"串行化"断言变成同义反复(恒真,抓不到任何竞态)
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> t1 = executor.submit(() -> service.chat("sid-race", "msg-1"));
                // 确认 T1 已持有该会话的锁并停在 assistant 里
                assertThat(t1Inside.await(10, TimeUnit.SECONDS))
                        .as("T1 应在超时前进入 assistant")
                        .isTrue();

                // 缺陷点:此刻持锁中的 T1 不能被摘掉
                service.evict("sid-race");

                // 主线程发起第二次调用。修复版会阻塞在 T1 持有的同一把锁上;
                // 缺陷版会拿到新锁并**立刻**进入 assistant → maxConcurrent 变成 2
                Future<?> t2 = executor.submit(() -> service.chat("sid-race", "msg-2"));
                // 给 T2 一点时间去"尝试"拿锁 —— 缺陷版在此期间就会进 assistant
                Thread.sleep(200);

                releaseT1.countDown();
                t1.get(10, TimeUnit.SECONDS);
                t2.get(10, TimeUnit.SECONDS);
            } finally {
                releaseT1.countDown();
                executor.shutdownNow();
            }

            assertThat(maxConcurrent.get())
                    .as("同一 chatSessionId 的并发调用必须严格串行,不能同时改 ChatMemory")
                    .isEqualTo(1);
        } finally {
            releaseT1.countDown();
        }
    }

    /**
     * 锁条目的回收必须发生在"没人持有、也没人排队"时。
     * 这里用反射读私有 map,直接断言 chat() 完成后条目仍在 —— 防止有人把
     * {@code serializationLocks.remove()} 又塞回 chat() 的 finally 里。
     */
    @Test
    @DisplayName("chat() 完成后不回收锁条目(排队线程仍持旧锁),回收交给定时任务")
    void chat_keepsLockEntryAfterCompletion() throws Exception {
        service.chat("sid-keep", "m1");
        service.chat("sid-keep", "m2");

        java.util.Map<String, ReentrantLock> locks = readLocks();

        assertThat(locks)
                .as("chat() 结束不能摘锁:排队中的线程仍持旧锁,摘除会让新线程拿到另一把锁")
                .containsKey("sid-keep");
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, ReentrantLock> readLocks() {
        try {
            java.lang.reflect.Field f =
                    ChatAssistantService.class.getDeclaredField("serializationLocks");
            f.setAccessible(true);
            return (java.util.Map<String, ReentrantLock>) f.get(service);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
        org.mockito.Mockito.verify(assistant, org.mockito.Mockito.times(1)).chat(eq("sid-1"), anyString(), eq("hi"));
        org.mockito.Mockito.verify(assistant, org.mockito.Mockito.times(1)).chat(eq("sid-1"), anyString(), eq("hi again"));
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
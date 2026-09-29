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
import java.time.LocalDate;
import java.time.ZoneId;
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

    /**
     * 影院业务时区。系统提示词里的"今天"必须按**影院本地日期**算,不能用注入的
     * {@code Clock}(默认 {@code Clock.systemUTC()})—— 北京时间 00:00~08:00 期间
     * UTC 日期还是昨天,LLM 会把"明天"换算成错误的场次日期(E2E 2026-09-29 P2-4 实测:
     * 助手把 2026-09-08 的过期场次当成"明天"推荐)。
     */
    private static final ZoneId CINEMA_ZONE = ZoneId.of("Asia/Shanghai");

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

        // 1. 拿串行化锁(同 chatSessionId 串行,不同并行 — LangChain4j 官方警告)
        ReentrantLock lock = serializationLocks.computeIfAbsent(chatSessionId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 2. idle 检查必须放在锁**内**。
            //    原来放在锁外:并发首访同一新会话时,两个线程都读到 lastAccessAt==null
            //    → 都走 evict() → serializationLocks.remove();后一个线程会把
            //    前一个线程**正在使用**的锁从 map 里删掉,于是第二个线程
            //    computeIfAbsent 拿到一把全新的锁,两个线程同时改同一个 ChatMemory,
            //    串行化直接失效(表现为偶发 maxConcurrent==2)。
            evictIfIdle(chatSessionId);

            String reply = assistant.chat(chatSessionId, currentDateContext(), message);
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
            // 关键:这里**不能**顺手把 lock 从 map 里摘掉。
            // 正在排队等这把锁的线程仍持旧锁;此时摘除,后续线程 computeIfAbsent 会拿到
            // 一把新锁,于是又出现两个线程并行。锁条目的回收统一交给 evictIdleSessions()。
            lock.unlock();
        }
    }

    /**
     * 拼给系统提示词的「当前日期」上下文(P2-4)。
     *
     * <p>给出今天/明天/后天三个绝对日期,让 LLM 能把"明天上午10点"这类相对表述
     * 准确换算成 {@code listSessions} 需要的 {@code yyyy-MM-dd},而不必自己猜。
     */
    String currentDateContext() {
        LocalDate today = LocalDate.now(CINEMA_ZONE);
        return "今天是 " + today + "(" + WEEKDAYS[today.getDayOfWeek().getValue() - 1] + "),"
                + "明天是 " + today.plusDays(1) + ",后天是 " + today.plusDays(2) + "。";
    }

    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /**
     * 清会话状态(ChatMemory + lastAccessAt),<b>不动串行化锁</b>。
     *
     * <p>锁条目的回收与状态清理必须分开:在 {@code chat()} 路径里调用时,锁正被本线程持有,
     * 此刻把它从 map 摘掉会让并发线程拿到一把新锁,串行化失效(见 {@code chat()} 注释)。
     */
    private void evictState(String chatSessionId) {
        memoryStore.evict(chatSessionId);
        lastAccessAt.remove(chatSessionId);
    }

    /**
     * 显式 evict(spec §4.3 + Q6):清状态 + 回收锁条目。
     *
     * <p>回收锁前先确认没人持有、也没人排队 —— 定时任务 {@link #evictIdleSessions()}
     * 可能在某次 chat 执行期间命中一条"看起来空闲"的记录,此时摘锁同样会造成并行。
     */
    public void evict(String chatSessionId) {
        log.info("[chat] evict chatSessionId={}", chatSessionId);
        evictState(chatSessionId);
        ReentrantLock lock = serializationLocks.get(chatSessionId);
        if (lock == null || (!lock.isLocked() && !lock.hasQueuedThreads())) {
            serializationLocks.remove(chatSessionId);
        }
    }

    /** spec Q1: 空闲 30min 自动 evict。<b>调用方必须已持有该会话的锁</b>(见 chat()) */
    private void evictIfIdle(String chatSessionId) {
        Long last = lastAccessAt.get(chatSessionId);
        if (last == null || clock.millis() - last > IDLE_TIMEOUT_MILLIS) {
            evictState(chatSessionId);
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
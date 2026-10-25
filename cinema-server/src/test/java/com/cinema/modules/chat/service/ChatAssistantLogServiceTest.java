package com.cinema.modules.chat.service;

import com.cinema.modules.chat.entity.ChatAssistantLog;
import com.cinema.modules.chat.mapper.ChatAssistantLogMapper;
import com.cinema.modules.chat.vo.ChatAssistantStatsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话助手「写审计 + 用量」服务单测(v0.4.0 IT-05 + IT-11)。
 *
 * <p>本类锁的是三条最容易悄悄坏掉的语义:
 * <ol>
 *   <li><b>审计写失败绝不影响主流程</b> —— 写库抛异常时 {@code recordChat} 不外抛。
 *       审计挂掉就让用户下单失败是不可接受的。</li>
 *   <li><b>用量未知记 null, 不记 0</b> —— 0 会被误读成「这次调用免费」。</li>
 *   <li><b>红线查询的口径</b> —— {@code kind='WRITE_CONFIRM' AND confirmed_by_user=0}
 *       的行数就是「未经用户确认的写操作」,必须恒为 0。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatAssistantLogServiceTest {

    @Mock private ChatAssistantLogMapper mapper;

    private ChatAssistantLogService service;

    @BeforeEach
    void setUp() {
        // 单价: 输入 15 美分/百万, 输出 60 美分/百万(2026-10-25 DeepSeek flash 非高峰价)
        service = new ChatAssistantLogService(mapper, 15, 60);
    }

    @Test
    @DisplayName("recordChat: 写入一行 CHAT, kind 正确, 事件带会话与用户")
    void recordChat_writesRow() {
        service.recordChat("sid-1", 100L, 120, 800, 200, ChatAssistantLog.RESULT_OK);

        ArgumentCaptor<ChatAssistantLog> cap = ArgumentCaptor.forClass(ChatAssistantLog.class);
        verify(mapper).insert(cap.capture());
        ChatAssistantLog row = cap.getValue();
        assertThat(row.getKind()).isEqualTo(ChatAssistantLog.KIND_CHAT);
        assertThat(row.getChatSessionId()).isEqualTo("sid-1");
        assertThat(row.getUserId()).isEqualTo(100L);
        assertThat(row.getLatencyMs()).isEqualTo(120);
        assertThat(row.getPromptTokens()).isEqualTo(800);
        assertThat(row.getCompletionTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("recordChat: 非写事件的 confirmed_by_user 是 null(不是 0 —— 0 是事故)")
    void recordChat_confirmedByUserIsNullForNonWrite() {
        service.recordChat("sid-1", 100L, 10, null, null, ChatAssistantLog.RESULT_OK);

        ArgumentCaptor<ChatAssistantLog> cap = ArgumentCaptor.forClass(ChatAssistantLog.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getConfirmedByUser()).isNull();
    }

    @Test
    @DisplayName("用量未知 → token 与 cost 都记 null(不记 0, 0 会被读成「免费」)")
    void recordChat_unknownUsage_isNullNotZero() {
        service.recordChat("sid-1", 100L, 10, null, null, ChatAssistantLog.RESULT_OK);

        ArgumentCaptor<ChatAssistantLog> cap = ArgumentCaptor.forClass(ChatAssistantLog.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getPromptTokens()).isNull();
        assertThat(cap.getValue().getCompletionTokens()).isNull();
        assertThat(cap.getValue().getCostCents()).isNull();
    }

    @Test
    @DisplayName("成本按整数运算: 100 万输入 + 100 万输出 = 15 + 60 = 75 美分")
    void recordChat_computesCost() {
        service.recordChat("sid-1", 100L, 10, 1_000_000, 1_000_000, ChatAssistantLog.RESULT_OK);

        ArgumentCaptor<ChatAssistantLog> cap = ArgumentCaptor.forClass(ChatAssistantLog.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getCostCents()).isEqualTo(75L);
    }

    @Test
    @DisplayName("成本四舍五入到美分(整数运算, 不碰浮点)")
    void recordChat_roundsToCent() {
        // 1 token 输入 @ 15 美分/百万 = 0.000015 美分 → 舍入到 0
        service.recordChat("sid-1", 100L, 10, 1, 0, ChatAssistantLog.RESULT_OK);

        ArgumentCaptor<ChatAssistantLog> cap = ArgumentCaptor.forClass(ChatAssistantLog.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getCostCents()).isEqualTo(0L);
    }

    @Test
    @DisplayName("单价未配置(0)→ 成本记 null, 不编造 0 成本")
    void recordChat_noUnitPrice_isNullCost() {
        ChatAssistantLogService noPrice = new ChatAssistantLogService(mapper, 0, 0);
        noPrice.recordChat("sid-1", 100L, 10, 1000, 1000, ChatAssistantLog.RESULT_OK);

        ArgumentCaptor<ChatAssistantLog> cap = ArgumentCaptor.forClass(ChatAssistantLog.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getCostCents()).isNull();
    }

    @Test
    @DisplayName("recordWriteConfirm: 写事件带 kind=WRITE_CONFIRM 与 confirmed_by_user=1")
    void recordWriteConfirm_marksUserConfirmed() {
        service.recordWriteConfirm("sid-1", 100L, "prop-1", "ORD-1",
                ChatAssistantLog.RESULT_OK, 1, 300);

        ArgumentCaptor<ChatAssistantLog> cap = ArgumentCaptor.forClass(ChatAssistantLog.class);
        verify(mapper).insert(cap.capture());
        ChatAssistantLog row = cap.getValue();
        assertThat(row.getKind()).isEqualTo(ChatAssistantLog.KIND_WRITE_CONFIRM);
        assertThat(row.getConfirmedByUser()).isEqualTo(1);
        assertThat(row.getProposalId()).isEqualTo("prop-1");
        assertThat(row.getOrderNo()).isEqualTo("ORD-1");
    }

    @Test
    @DisplayName("⚠️ 审计写库抛异常时**不外抛** —— 审计挂掉绝不能让用户下单失败")
    void recordChat_dbFailure_doesNotThrow() {
        when(mapper.insert(any(ChatAssistantLog.class)))
                .thenThrow(new RuntimeException("模拟 DB 抖动"));

        assertThatCode(() -> service.recordChat("sid-1", 100L, 10, 1, 1, ChatAssistantLog.RESULT_OK))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("红线查询: 未确认写操作数委派给 mapper(必须恒为 0)")
    void countUnconfirmedWrites_delegates() {
        when(mapper.countUnconfirmedWrites()).thenReturn(0);
        assertThat(service.countUnconfirmedWrites()).isZero();
    }

    @Test
    @DisplayName("stats: 汇总四项指标 + 红线指标, 失败率按比例算")
    void stats_aggregates() {
        Map<String, Object> row = new HashMap<>();
        row.put("total", 10L);
        row.put("failed", 2L);
        row.put("unconfirmed_writes", 0L);
        row.put("write_attempts", 4L);
        row.put("write_success", 3L);
        row.put("prompt_tokens", 1000L);
        row.put("completion_tokens", 200L);
        row.put("cost_cents", 30L);
        row.put("sessions", 5L);
        row.put("users", 3L);
        when(mapper.summarize(any(LocalDateTime.class))).thenReturn(row);
        when(mapper.p95LatencyMs(any(LocalDateTime.class))).thenReturn(900);
        when(mapper.callsByDay(any(LocalDateTime.class))).thenReturn(List.of(Map.of("day", "2026-10-25", "calls", 10L)));

        ChatAssistantStatsVO vo = service.stats(7);

        assertThat(vo.getTotalCalls()).isEqualTo(10L);
        assertThat(vo.getFailedCalls()).isEqualTo(2L);
        assertThat(vo.getFailureRate()).isEqualTo(0.2d);
        assertThat(vo.getUnconfirmedWrites()).isZero();
        assertThat(vo.getWriteAttempts()).isEqualTo(4L);
        assertThat(vo.getWriteSuccess()).isEqualTo(3L);
        assertThat(vo.getP95LatencyMs()).isEqualTo(900);
        assertThat(vo.getCostCents()).isEqualTo(30L);
        assertThat(vo.getDailyCalls()).hasSize(1);
    }

    @Test
    @DisplayName("stats: 无样本时失败率是 0 而不是 NaN")
    void stats_noSamples_failureRateIsZero() {
        Map<String, Object> row = new HashMap<>();
        row.put("total", 0L);
        row.put("failed", 0L);
        when(mapper.summarize(any(LocalDateTime.class))).thenReturn(row);

        ChatAssistantStatsVO vo = service.stats(7);

        assertThat(vo.getFailureRate()).isZero();
        assertThat(vo.getTotalCalls()).isZero();
    }

    @Test
    @DisplayName("stats: 窗口 ≤ 0 时按 7 天处理")
    void stats_nonPositiveWindow_fallsBackTo7() {
        Map<String, Object> row = new HashMap<>();
        row.put("total", 0L);
        when(mapper.summarize(any(LocalDateTime.class))).thenReturn(row);

        assertThat(service.stats(0).getDays()).isEqualTo(7);
        assertThat(service.stats(-3).getDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("tableExists: 探到表不存在时返 false(迁移没跑)")
    void tableExists_false() {
        when(mapper.countTableExists()).thenReturn(0);
        assertThat(service.tableExists()).isFalse();
    }

    @Test
    @DisplayName("tableExists: 探测本身抛异常时也返 false(不炸启动)")
    void tableExists_exceptionIsSwallowed() {
        when(mapper.countTableExists()).thenThrow(new RuntimeException("表不存在"));
        assertThat(service.tableExists()).isFalse();
    }
}

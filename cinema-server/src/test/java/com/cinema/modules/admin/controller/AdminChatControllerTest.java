package com.cinema.modules.admin.controller;

import com.cinema.modules.chat.service.ChatAssistantLogService;
import com.cinema.modules.chat.vo.ChatAssistantStatsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端「助手用量看板」端点单测(v0.4.0 IT-11)。
 *
 * <p>本类锁两件事:
 * <ol>
 *   <li>端点把 {@code days} 透传给 service(缺省 7 天)。</li>
 *   <li>响应体带回红线指标 —— 让「助手没越权写」是**可查证的**,而不是口头保证。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminChatControllerTest {

    @Mock private ChatAssistantLogService logService;

    private AdminChatController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminChatController(logService);
    }

    @Test
    @DisplayName("stats: 缺省统计近 7 天, 并把红线指标带回响应")
    void stats_defaultsTo7Days() {
        ChatAssistantStatsVO vo = new ChatAssistantStatsVO();
        vo.setDays(7);
        vo.setTotalCalls(10L);
        vo.setFailedCalls(1L);
        vo.setFailureRate(0.1d);
        vo.setUnconfirmedWrites(0L);
        when(logService.stats(7)).thenReturn(vo);

        var r = controller.stats(7);

        verify(logService).stats(7);
        assertThat(r.getCode()).isZero();
        assertThat(r.getData()).isSameAs(vo);
        assertThat(r.getData().getUnconfirmedWrites()).isZero();
    }

    @Test
    @DisplayName("stats: days 透传(30 天窗口)")
    void stats_passesDaysThrough() {
        ChatAssistantStatsVO vo = new ChatAssistantStatsVO();
        vo.setDays(30);
        when(logService.stats(30)).thenReturn(vo);

        var r = controller.stats(30);

        verify(logService).stats(30);
        assertThat(r.getData().getDays()).isEqualTo(30);
    }
}

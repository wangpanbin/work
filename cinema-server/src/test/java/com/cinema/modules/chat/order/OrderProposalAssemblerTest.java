package com.cinema.modules.chat.order;

import com.cinema.common.exception.BizException;
import com.cinema.modules.chat.vo.ActionCardVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderProposalAssembler 单元测试 (ADR-0004)。
 *
 * <p>装配层是整个「一键下单」的安全闸门, 锁住四条:
 * <ol>
 *   <li>既有卡型(SEAT_SUGGESTION)必须**原样透传** —— 不能被下单逻辑污染</li>
 *   <li>ORDER_PROPOSAL 卡必须被换成**服务端核过**的值(座位/价格/提案 ID)</li>
 *   <li>建提案失败 → **丢那张卡**并在回复里给出说明, 而不是让整轮对话炸掉</li>
 *   <li>开关关闭 / 未登录 → 不建任何提案</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderProposalAssemblerTest {

    private static final long USER_ID = 7L;

    @Mock private OrderProposalService proposalService;

    private ActionCardVO proposalCard() {
        return ActionCardVO.builder()
                .type(ActionCardVO.TYPE_ORDER_PROPOSAL)
                .sessionId("1001")
                .count(2)
                .seatIndexes(List.of(52, 53))
                .seatDesc("模型说的座位")
                .price(new BigDecimal("999.00"))   // 模型幻觉的高价
                .totalAmount(new BigDecimal("1998.00"))
                .actionLabel("模型写的按钮")
                .build();
    }

    private OrderProposal serverProposal() {
        return OrderProposal.builder()
                .proposalId("P-SERVER")
                .userId(USER_ID)
                .sessionId(1001L)
                .seatIndexes(List.of(40, 41))
                .seatDesc("4排5座、4排6座")
                .unitPrice(new BigDecimal("45.00"))
                .totalAmount(new BigDecimal("90.00"))
                .movieTitle("流浪地球")
                .hallName("1号厅")
                .startTime("2026-10-20 20:00:00")
                .expiresAt(1_800_000_000_000L)
                .pendingCancelOrderNo("ORD-OLD")
                .pendingCancelExpireAt(1_799_999_000_000L)
                .build();
    }

    @Test
    @DisplayName("既有卡型(SEAT_SUGGESTION)原样透传, 一次提案都不建")
    void suggestionCard_thenPassedThroughUntouched() {
        ActionCardVO suggestion = ActionCardVO.builder()
                .type(ActionCardVO.TYPE_SEAT_SUGGESTION)
                .sessionId("1001")
                .seatIndexes(List.of(52, 53))
                .build();
        OrderProposalAssembler assembler = new OrderProposalAssembler(proposalService, true);

        OrderProposalAssembler.AssembleResult r = assembler.assemble(USER_ID, List.of(suggestion));

        assertThat(r.cards()).hasSize(1);
        assertThat(r.cards().get(0)).isSameAs(suggestion);
        assertThat(r.cards().get(0).getProposalId()).isNull();
        assertThat(r.note()).isNull();
        verify(proposalService, never()).createProposal(anyLong(), anyLong(), any(), anyList());
    }

    @Test
    @DisplayName("ORDER_PROPOSAL 卡 → 座位/价格被换成服务端值, 填上 proposalId 与存量待支付单号")
    void proposalCard_thenOverwrittenByServerValues() {
        when(proposalService.createProposal(eq(USER_ID), eq(1001L), eq(2), anyList()))
                .thenReturn(serverProposal());
        OrderProposalAssembler assembler = new OrderProposalAssembler(proposalService, true);

        OrderProposalAssembler.AssembleResult r = assembler.assemble(USER_ID, List.of(proposalCard()));

        assertThat(r.cards()).hasSize(1);
        ActionCardVO c = r.cards().get(0);
        // 服务端核过的座位
        assertThat(c.getSeatIndexes()).containsExactly(40, 41);
        assertThat(c.getSeatDesc()).isEqualTo("4排5座、4排6座");
        // 模型的价格被丢弃
        assertThat(c.getPrice()).isEqualByComparingTo(new BigDecimal("45.00"));
        assertThat(c.getTotalAmount()).isEqualByComparingTo(new BigDecimal("90.00"));
        // 提案引用 + 过期时刻 + 存量待支付单
        assertThat(c.getProposalId()).isEqualTo("P-SERVER");
        assertThat(c.getExpiresAt()).isEqualTo(1_800_000_000_000L);
        assertThat(c.getPendingCancelOrderNo()).isEqualTo("ORD-OLD");
        assertThat(c.getCount()).isEqualTo(2);
        // 稳定的 cardId 给前端 v-for key
        assertThat(c.getCardId()).isEqualTo("P-SERVER");
        // 按钮文案交给前端 i18n, 后端不塞中文
        assertThat(c.getActionLabel()).isNull();
        assertThat(r.note()).isNull();
    }

    @Test
    @DisplayName("建提案失败 → 丢那张卡 + 给出说明, 既有卡不受影响")
    void proposalFailed_thenCardDroppedWithNote() {
        when(proposalService.createProposal(eq(USER_ID), eq(1001L), any(), any()))
                .thenThrow(new BizException("该场次没有 2 个连座可选"));
        ActionCardVO suggestion = ActionCardVO.builder()
                .type(ActionCardVO.TYPE_SEAT_SUGGESTION)
                .sessionId("1001")
                .seatIndexes(List.of(52, 53))
                .build();
        OrderProposalAssembler assembler = new OrderProposalAssembler(proposalService, true);

        OrderProposalAssembler.AssembleResult r = assembler.assemble(USER_ID, List.of(proposalCard(), suggestion));

        assertThat(r.cards()).hasSize(1);
        assertThat(r.cards().get(0).getType()).isEqualTo(ActionCardVO.TYPE_SEAT_SUGGESTION);
        assertThat(r.note()).contains("连座");
    }

    @Test
    @DisplayName("未登录 → 不建提案, 丢卡并提示先登录")
    void anonymous_thenNoProposalAndLoginHint() {
        OrderProposalAssembler assembler = new OrderProposalAssembler(proposalService, true);

        OrderProposalAssembler.AssembleResult r = assembler.assemble(null, List.of(proposalCard()));

        assertThat(r.cards()).isEmpty();
        assertThat(r.note()).contains("登录");
        verify(proposalService, never()).createProposal(anyLong(), anyLong(), any(), anyList());
    }

    @Test
    @DisplayName("order-enabled=false → 不建提案, 丢卡并说明功能未启用")
    void disabled_thenNoProposal() {
        OrderProposalAssembler assembler = new OrderProposalAssembler(proposalService, false);

        OrderProposalAssembler.AssembleResult r = assembler.assemble(USER_ID, List.of(proposalCard()));

        assertThat(r.cards()).isEmpty();
        assertThat(r.note()).isNotBlank();
        verify(proposalService, never()).createProposal(anyLong(), anyLong(), any(), anyList());
    }

    @Test
    @DisplayName("场次 ID 非法(模型给了非数字) → 丢卡, 不去调建提案")
    void invalidSessionId_thenCardDropped() {
        ActionCardVO bad = ActionCardVO.builder()
                .type(ActionCardVO.TYPE_ORDER_PROPOSAL)
                .sessionId("not-a-number")
                .count(2)
                .build();
        OrderProposalAssembler assembler = new OrderProposalAssembler(proposalService, true);

        OrderProposalAssembler.AssembleResult r = assembler.assemble(USER_ID, List.of(bad));

        assertThat(r.cards()).isEmpty();
        assertThat(r.note()).isNotBlank();
        verify(proposalService, never()).createProposal(anyLong(), anyLong(), any(), anyList());
    }

    @Test
    @DisplayName("空卡片列表 → 原样返回, 不建提案")
    void emptyCards_thenNoop() {
        OrderProposalAssembler assembler = new OrderProposalAssembler(proposalService, true);

        OrderProposalAssembler.AssembleResult r = assembler.assemble(USER_ID, List.of());

        assertThat(r.cards()).isEmpty();
        assertThat(r.note()).isNull();
        verify(proposalService, never()).createProposal(anyLong(), anyLong(), any(), anyList());
    }
}

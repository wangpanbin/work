package com.cinema.modules.chat.controller;

import com.cinema.common.annotation.IdempotencyKeys;
import com.cinema.common.annotation.Idempotent;
import com.cinema.common.annotation.RateLimit;
import com.cinema.common.context.UserContext;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.chat.order.OrderProposal;
import com.cinema.modules.chat.order.OrderProposalService;
import com.cinema.modules.chat.order.dto.OrderProposalDTO;
import com.cinema.modules.chat.order.vo.OrderProposalVO;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.service.OrderLockService;
import com.cinema.modules.order.vo.LockResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatOrderController 单元测试 (ADR-0004)。
 *
 * <p>锁住四条关键契约:
 * <ol>
 *   <li><b>座位只来自服务端快照</b> —— 请求体里根本没有座位字段</li>
 *   <li><b>幂等键 = proposalId</b>, 不是 userId:sessionId:seatIndexes</li>
 *   <li><b>回滚闸门</b>: order-enabled=false 时端点拒服务</li>
 *   <li><b>未登录拒服务</b>(40101), 端点自身还有一道防线(不只靠拦截器)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatOrderControllerTest {

    private static final long USER_ID = 7L;

    @Mock private OrderProposalService proposalService;
    @Mock private OrderLockService orderLockService;

    private ChatOrderController controller(boolean enabled) {
        return new ChatOrderController(proposalService, orderLockService, enabled);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private OrderProposal proposal() {
        return OrderProposal.builder()
                .proposalId("P-1")
                .userId(USER_ID)
                .sessionId(1001L)
                .seatIndexes(List.of(40, 41))
                .seatDesc("4排5座、4排6座")
                .unitPrice(new BigDecimal("45.00"))
                .totalAmount(new BigDecimal("90.00"))
                .movieTitle("流浪地球")
                .hallName("1号厅")
                .startTime("2026-10-20 20:00:00")
                .build();
    }

    // =================================================================
    // 确认下单
    // =================================================================

    @Test
    @DisplayName("确认成功 → 锁座用的座位来自服务端快照, 响应带 orderNo 与支付窗口")
    void confirm_thenLocksSeatsFromSnapshot() {
        UserContext.set(USER_ID, "u7", 0);
        when(proposalService.consumeProposal("P-1", USER_ID)).thenReturn(proposal());
        when(orderLockService.lockSeats(eq(USER_ID), any(LockSeatsDTO.class)))
                .thenReturn(new LockResultVO("ORD-9", LocalDateTime.now().plusMinutes(15),
                        new BigDecimal("90.00"), List.of(40, 41)));

        R<OrderProposalVO> r = controller(true).confirm("P-1");

        assertThat(r.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
        assertThat(r.getData().getOrderNo()).isEqualTo("ORD-9");
        assertThat(r.getData().getOrderExpireAt()).isNotNull();
        assertThat(r.getData().getSeatDesc()).isEqualTo("4排5座、4排6座");

        // 座位与场次都来自快照, 不是客户端传的
        ArgumentCaptor<LockSeatsDTO> captor = ArgumentCaptor.forClass(LockSeatsDTO.class);
        verify(orderLockService).lockSeats(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getSeatIndexes()).containsExactly(40, 41);
        assertThat(captor.getValue().getSessionId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("提案不存在/已过期 → 业务错误透出, 不落任何锁座副作用")
    void confirmWithExpiredProposal_thenBusinessError() {
        UserContext.set(USER_ID, "u7", 0);
        when(proposalService.consumeProposal("P-gone", USER_ID))
                .thenThrow(new BizException(ResultCode.PROPOSAL_EXPIRED.getCode(),
                        "下单方案已过期,请重新发起", null));

        assertThatThrownBy(() -> controller(true).confirm("P-gone"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("过期");
        verify(orderLockService, never()).lockSeats(any(), any());
    }

    @Test
    @DisplayName("订单开关关闭 → 端点拒服务(回滚闸门), 不碰提案也不锁座")
    void confirmWhenDisabled_thenRejected() {
        UserContext.set(USER_ID, "u7", 0);

        R<OrderProposalVO> r = controller(false).confirm("P-1");

        assertThat(r.getCode()).isEqualTo(ResultCode.ORDER_DISABLED.getCode());
        assertThat(r.getData()).isNull();
        verify(proposalService, never()).consumeProposal(any(), any());
        verify(orderLockService, never()).lockSeats(any(), any());
    }

    @Test
    @DisplayName("未登录 → 40101, 不碰提案也不锁座")
    void confirmWhenAnonymous_thenUnauthorized() {
        R<OrderProposalVO> r = controller(true).confirm("P-1");

        assertThat(r.getCode()).isEqualTo(ResultCode.UNAUTHORIZED.getCode());
        assertThat(r.getData()).isNull();
        verify(proposalService, never()).consumeProposal(any(), any());
        verify(orderLockService, never()).lockSeats(any(), any());
    }

    // =================================================================
    // 重新找一组
    // =================================================================

    @Test
    @DisplayName("重新找一组 → 按场次与张数建提案, 响应带服务端核过的座位与价格")
    void createProposal_thenReturnsServerSnapshot() {
        UserContext.set(USER_ID, "u7", 0);
        when(proposalService.createProposal(eq(USER_ID), eq(1001L), eq(2), eq(null)))
                .thenReturn(proposal());
        OrderProposalDTO dto = new OrderProposalDTO();
        dto.setSessionId(1001L);
        dto.setCount(2);

        R<OrderProposalVO> r = controller(true).createProposal(dto);

        assertThat(r.getCode()).isEqualTo(ResultCode.SUCCESS.getCode());
        assertThat(r.getData().getProposalId()).isEqualTo("P-1");
        assertThat(r.getData().getSeatIndexes()).containsExactly(40, 41);
        assertThat(r.getData().getTotalAmount()).isEqualByComparingTo(new BigDecimal("90.00"));
        // 场次 ID 以字符串下发 —— 雪花 ID 超 2^53, 用 number 会丢精度
        assertThat(r.getData().getSessionId()).isEqualTo("1001");
    }

    @Test
    @DisplayName("重新找一组: 订单开关关闭 → 拒服务")
    void createProposalWhenDisabled_thenRejected() {
        UserContext.set(USER_ID, "u7", 0);
        OrderProposalDTO dto = new OrderProposalDTO();
        dto.setSessionId(1001L);
        dto.setCount(2);

        R<OrderProposalVO> r = controller(false).createProposal(dto);

        assertThat(r.getCode()).isEqualTo(ResultCode.ORDER_DISABLED.getCode());
        verify(proposalService, never()).createProposal(any(), any(), any(), any());
    }

    // =================================================================
    // 门禁注解(反射断言, 对齐 ChatControllerTest 既有风格)
    // =================================================================

    @Test
    @DisplayName("confirm 方法上的 @Idempotent 幂等键 = proposalId(不是 userId:sessionId:seatIndexes)")
    void confirm_hasIdempotentKeyOnProposalId() throws NoSuchMethodException {
        Method m = ChatOrderController.class.getMethod("confirm", String.class);
        Idempotent annotation = m.getAnnotation(Idempotent.class);

        assertThat(annotation).as("@Idempotent 必须就位(重复点不能锁两次座)").isNotNull();
        assertThat(annotation.key()).isEqualTo(IdempotencyKeys.CHAT_ORDER_CONFIRM);
        assertThat(annotation.ttl()).isEqualTo(8);
    }

    @Test
    @DisplayName("confirm 方法上的 @RateLimit 限流配置正确(3 次/分钟)")
    void confirm_hasRateLimitAnnotation() throws NoSuchMethodException {
        Method m = ChatOrderController.class.getMethod("confirm", String.class);
        RateLimit annotation = m.getAnnotation(RateLimit.class);

        assertThat(annotation).as("@RateLimit 必须就位(聊天端点不走 /orders/lock 的限流桶)").isNotNull();
        assertThat(annotation.permits()).isEqualTo(3);
        assertThat(annotation.window()).isEqualTo(1);
        assertThat(annotation.unit()).isEqualTo(TimeUnit.MINUTES);
    }

    @Test
    @DisplayName("createProposal 方法上的 @RateLimit 限流配置正确(10 次/分钟)")
    void createProposal_hasRateLimitAnnotation() throws NoSuchMethodException {
        Method m = ChatOrderController.class.getMethod("createProposal", OrderProposalDTO.class);
        RateLimit annotation = m.getAnnotation(RateLimit.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.permits()).isEqualTo(10);
        assertThat(annotation.window()).isEqualTo(1);
        assertThat(annotation.unit()).isEqualTo(TimeUnit.MINUTES);
    }
}

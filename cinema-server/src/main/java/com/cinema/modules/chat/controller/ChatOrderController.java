package com.cinema.modules.chat.controller;

import com.cinema.common.annotation.Idempotent;
import com.cinema.common.annotation.RateLimit;
import com.cinema.common.context.UserContext;
import com.cinema.common.result.R;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.chat.order.OrderProposal;
import com.cinema.modules.chat.order.OrderProposalService;
import com.cinema.modules.chat.order.dto.OrderProposalDTO;
import com.cinema.modules.chat.order.vo.OrderProposalVO;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.service.OrderLockService;
import com.cinema.modules.order.vo.LockResultVO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 对话助手的下单端点(ADR-0004)。
 *
 * <p><b>这是 chat 模块第一个也是唯一一个写入口</b>。它做两件事:
 * <ol>
 *   <li>{@code POST /api/chat/orders/proposals} —— 建/重建提案(只读语义, 不占座)</li>
 *   <li>{@code POST /api/chat/orders/{proposalId}/confirm} —— 确认下单(真正锁座)</li>
 * </ol>
 *
 * <p><b>安全设计</b>(逐条回应 ADR-0002 的反对理由):
 * <ul>
 *   <li><b>座位只来自服务端快照</b>:confirm 的请求体里<b>没有</b>座位字段,
 *       座位从 Redis 里的提案快照读出。模型幻觉的座位列表永远进不了锁座链路。</li>
 *   <li><b>幂等键 = proposalId</b>:不是 {@code userId:sessionId:seatIndexes}。
 *       模型重试或幻觉产生不了第二把钥匙(ADR-0002 证据 2)。</li>
 *   <li><b>提案一次性</b>:读出即删(原子 GETDEL),同一份快照不可能被用两次。</li>
 *   <li><b>归属校验</b>:提案里存了 userId,与当前登录态不符直接拒绝(防 IDOR)。</li>
 *   <li><b>既有门禁不绕开</b>:confirm 自己挂 {@code @Idempotent} + {@code @RateLimit},
 *       内部复用 {@code OrderLockService#lockSeats} —— Redis Bitmap + Lua CAS +
 *       延迟关单 + 补偿释放那一整套保护原样生效(ADR-0002 证据 4)。</li>
 * </ul>
 *
 * <p><b>鉴权</b>:{@code /api/chat/orders/**} 已被加入
 * {@code AuthRequiredInterceptor} 的覆盖路径(见 {@code WebConfig}),
 * 未登录访问直接返 40101。{@code /api/chat/message} 仍然公开可匿名,现状不变。
 *
 * <p><b>回滚闸门</b>:{@code cinema.chat.order-enabled=false} 时两个端点都拒服务,
 * 不发版即可让整条写路径失效。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/orders")
public class ChatOrderController {

    private final OrderProposalService proposalService;
    private final OrderLockService orderLockService;
    private final boolean orderEnabled;

    public ChatOrderController(OrderProposalService proposalService,
                               OrderLockService orderLockService,
                               @Value("${cinema.chat.order-enabled:true}") boolean orderEnabled) {
        this.proposalService = proposalService;
        this.orderLockService = orderLockService;
        this.orderEnabled = orderEnabled;
    }

    /**
     * 重新找一组座位(同场次同张数)。
     *
     * <p>座位由服务端重读位图挑, 客户端无从指定 —— 与助手自动出卡走的是同一套逻辑。
     */
    @PostMapping("/proposals")
    @RateLimit(key = "(T(com.cinema.common.context.UserContext).userId() ?: 'anon') + ':chat:proposal:' + #dto.sessionId",
            permits = 10, window = 1, unit = TimeUnit.MINUTES,
            message = "生成下单方案过于频繁,请稍后再试")
    public R<OrderProposalVO> createProposal(@RequestBody @Valid OrderProposalDTO dto) {
        if (!orderEnabled) {
            return R.fail(ResultCode.BUSINESS_ERROR.getCode(), "下单功能当前未启用");
        }
        Long userId = UserContext.userId();
        if (userId == null) {
            return R.fail(ResultCode.UNAUTHORIZED);
        }
        OrderProposal proposal = proposalService.createProposal(userId, dto.getSessionId(), dto.getCount(), null);
        return R.ok(OrderProposalVO.of(proposal));
    }

    /**
     * 确认下单 —— 按提案快照锁座。
     *
     * <p>座位、单价、总额全部来自服务端快照; 请求体只有一个 proposalId。
     */
    @PostMapping("/{proposalId}/confirm")
    @Idempotent(key = "#proposalId", ttl = 8, message = "下单请求处理中,请勿重复点击")
    @RateLimit(key = "(T(com.cinema.common.context.UserContext).userId() ?: 'anon') + ':chat:confirm:' + #proposalId",
            permits = 3, window = 1, unit = TimeUnit.MINUTES,
            message = "下单操作过于频繁,请稍后再试")
    public R<OrderProposalVO> confirm(@PathVariable String proposalId) {
        if (!orderEnabled) {
            return R.fail(ResultCode.BUSINESS_ERROR.getCode(), "下单功能当前未启用");
        }
        Long userId = UserContext.userId();
        if (userId == null) {
            return R.fail(ResultCode.UNAUTHORIZED);
        }
        // 一次性消费: 读出即删 + 归属校验
        OrderProposal proposal = proposalService.consumeProposal(proposalId, userId);

        // 座位只来自快照 —— 不接受任何客户端传入的座位
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(proposal.getSessionId());
        dto.setSeatIndexes(proposal.getSeatIndexes());

        LockResultVO lock = orderLockService.lockSeats(userId, dto);
        log.info("[chat-order] 确认下单 proposalId={} user={} orderNo={}",
                proposalId, userId, lock.getOrderNo());
        return R.ok(OrderProposalVO.ofLocked(proposal, lock));
    }
}

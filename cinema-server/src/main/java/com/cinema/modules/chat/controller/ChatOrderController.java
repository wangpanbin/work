package com.cinema.modules.chat.controller;

import com.cinema.common.annotation.IdempotencyKeys;
import com.cinema.common.annotation.Idempotent;
import com.cinema.common.annotation.RateLimit;
import com.cinema.common.context.UserContext;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.R;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.chat.entity.ChatAssistantLog;
import com.cinema.modules.chat.order.OrderProposal;
import com.cinema.modules.chat.order.OrderProposalService;
import com.cinema.modules.chat.order.dto.OrderProposalDTO;
import com.cinema.modules.chat.order.vo.OrderProposalVO;
import com.cinema.modules.chat.service.ChatAssistantLogService;
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
    /** 写审计落库服务(IT-05)。 */
    private final ChatAssistantLogService logService;

    public ChatOrderController(OrderProposalService proposalService,
                               OrderLockService orderLockService,
                               @Value("${cinema.chat.order-enabled:true}") boolean orderEnabled,
                               ChatAssistantLogService logService) {
        this.proposalService = proposalService;
        this.orderLockService = orderLockService;
        this.orderEnabled = orderEnabled;
        this.logService = logService;
    }

    /**
     * 重新找一组座位(同场次同张数)。
     *
     * <p>座位由服务端重读位图挑, 客户端无从指定 —— 与助手自动出卡走的是同一套逻辑。
     */
    @PostMapping("/proposals")
    @RateLimit(key = IdempotencyKeys.RATE_CHAT_PROPOSAL_BY_USER,
            permits = 10, window = 1, unit = TimeUnit.MINUTES,
            message = "生成下单方案过于频繁,请稍后再试")
    public R<OrderProposalVO> createProposal(@RequestBody @Valid OrderProposalDTO dto) {
        if (!orderEnabled) {
            return R.fail(ResultCode.ORDER_DISABLED.getCode(), "下单功能当前未启用");
        }
        // 纵深防御: AuthRequiredInterceptor 已先返 40101, 这里再挡一道 ——
        // 万一拦截器路径配置被改, 也不至于带着 null userId 去建提案。
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
    @Idempotent(key = IdempotencyKeys.CHAT_ORDER_CONFIRM, ttl = 8, message = "下单请求处理中,请勿重复点击")
    @RateLimit(key = IdempotencyKeys.RATE_CHAT_CONFIRM_BY_USER,
            permits = 3, window = 1, unit = TimeUnit.MINUTES,
            message = "下单操作过于频繁,请稍后再试")
    public R<OrderProposalVO> confirm(@PathVariable String proposalId) {
        // 纵深防御: AuthRequiredInterceptor 已先返 40101, 这里再挡一道 ——
        // 万一拦截器路径配置被改, 也不至于带着 null userId 去建提案。
        Long userId = UserContext.userId();
        if (!orderEnabled) {
            // 用户确实点了确认 —— 记一条 DISABLED, 不算事故(confirmed_by_user=1)
            recordAudit(userId, proposalId, null, ChatAssistantLog.RESULT_DISABLED);
            return R.fail(ResultCode.ORDER_DISABLED.getCode(), "下单功能当前未启用");
        }
        if (userId == null) {
            return R.fail(ResultCode.UNAUTHORIZED);
        }
        // 一次性消费: 读出即删 + 归属校验
        OrderProposal proposal;
        try {
            proposal = proposalService.consumeProposal(proposalId, userId);
        } catch (BizException e) {
            // 提案过期/已被消费/不属于当前用户 —— 也要留痕(失败率指标)
            recordAudit(userId, proposalId, null, ChatAssistantLog.RESULT_EXPIRED);
            throw e;
        }

        // 座位只来自快照 —— 不接受任何客户端传入的座位
        LockSeatsDTO dto = new LockSeatsDTO();
        dto.setSessionId(proposal.getSessionId());
        dto.setSeatIndexes(proposal.getSeatIndexes());

        LockResultVO lock;
        try {
            lock = orderLockService.lockSeats(userId, dto);
        } catch (BizException e) {
            // 座位被抢(40002)—— 留痕, 但订单没建
            recordAudit(userId, proposalId, null, ChatAssistantLog.RESULT_CONFLICT);
            throw e;
        }
        // 写审计(IT-05): 记在锁座**之后** —— 这样 orderNo 与最终结果一致,
        // 不会出现「审计说成功但订单没建」。
        recordAudit(userId, proposalId, lock.getOrderNo(), ChatAssistantLog.RESULT_OK);
        log.info("[chat-order] 确认下单 proposalId={} user={} orderNo={}",
                proposalId, userId, lock.getOrderNo());
        return R.ok(OrderProposalVO.ofLocked(proposal, lock));
    }

    /**
     * 记一条「确认下单」写审计(IT-05 红线口径)。
     *
     * <p><b>confirmedByUser 恒为 1</b>: 这个端点本身就是「用户在确认卡上点了确认」
     * 这一动作 —— 用户确实显式确认了。将来若新增任何**非**用户确认触发的写入口,
     * 那条路径必须显式传 0, 红线查询(未确认写数)会立刻把它数出来。
     *
     * <p><b>审计写失败绝不影响下单结果</b> —— service 层已 try/catch, 这里再兜一层。
     */
    private void recordAudit(Long userId, String proposalId, String orderNo, String result) {
        if (logService == null) {
            return;
        }
        try {
            // chatSessionId 留空: 确认端点拿不到对话会话 id(快照里没有),
            // 写事件靠 userId + proposalId 归属。
            logService.recordWriteConfirm("", userId, proposalId, orderNo, result, 1, null);
        } catch (Exception e) {
            log.warn("[chat-log] 记录写审计失败 proposalId={}: {}", proposalId, e.getMessage());
        }
    }
}

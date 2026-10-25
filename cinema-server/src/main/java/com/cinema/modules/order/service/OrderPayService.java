package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.redis.SeatWrite;
import com.cinema.infra.ws.AdminEventPublisher;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.RefundLog;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.mapper.RefundLogMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.payment.service.MockPaymentService;
import com.cinema.modules.payment.service.MockRefundService;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 订单支付 / 退款 — E1 拆分的 service 之一。
 * <p>E1 拆分: CAS 推进 PENDING_PAY → PAID
 * <p>N1 扩展: CAS 推进 PAID → REFUNDING → REFUNDED, 写 refund_log
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPayService {

    private final OrderMapper orderMapper;
    private final RefundLogMapper refundLogMapper;
    private final SeatLuaService seatLuaService;
    private final SeatEventPublisher seatEventPublisher;
    private final AdminEventPublisher adminEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final MockPaymentService mockPaymentService;
    private final MockRefundService mockRefundService;
    private final SessionMapper sessionMapper;
    private final OrderCore orderCore;
    private final TicketService ticketService;
    private final SeatBitmapGuard seatBitmapGuard;

    /**
     * 支付确认(锁位 → 售位), 位图写失败时**当场重建**(2026-10-24)。
     *
     * <p><b>为什么必须有兜底</b>: 支付是「先 CAS 改订单状态, 再写位图」两步。
     * CAS 已把订单推进到 PAID, 这时若 {@code confirm_seat.lua} 抛错(Redis 抖动),
     * 订单是 PAID 而位图还停在 lock=1 / sold=0 —— 座位对所有人永久显示「被锁」。
     * 而超时扫描器与补偿任务**只捞 status=0 的待支付单**, 没有任何流程会再碰这笔单,
     * 座位只能靠人工跑 {@code POST /api/admin/sessions/bitmaps/recover} 才能回来。
     *
     * <p><b>补救方式</b>: 从 DB 重建该场次位图(DB 里这笔单已是 PAID,
     * 重建出来的 sold 位会把它正确置上)。重建失败也**不删位图** ——
     * 删掉等于把「座位被锁死」变成「座位看起来可选」, 那是**超卖**方向, 比锁死更危险。
     * 宁可锁死(安全)也不可误放(超卖), 此时记 error 日志, 提示走管理端手动恢复。
     */
    private void confirmPaidWithBitmapRecovery(Order order, List<Integer> seats) {
        try {
            seatLuaService.confirmPaid(order.getSessionId(), seats);
        } catch (Exception e) {
            log.error("[支付] orderNo={} 位图置售失败(订单已是 PAID), 尝试从 DB 重建该场次位图 err={}",
                    order.getOrderNo(), e.toString());
            try {
                seatBitmapGuard.forceRecover(order.getSessionId());
                log.warn("[支付] orderNo={} 位图已从 DB 重建, 座位恢复为已售", order.getOrderNo());
            } catch (Exception ex) {
                log.error("[支付] orderNo={} 位图重建也失败, 该场次座位将持续显示为已锁, "
                                + "需管理端手动恢复位图 POST /api/admin/sessions/bitmaps/recover err={}",
                        order.getOrderNo(), ex.toString());
            }
        }
    }

    /** 模拟支付 */
    public void pay(String orderNo, Long userId) {
        Order order = orderCore.getOwnedOrder(orderNo, userId);
        if (order.getStatus() == OrderStatus.PAID.getCode()) {
            throw new BizException("订单已支付,请勿重复操作");
        }
        if (order.getStatus() == OrderStatus.CANCELLED.getCode()) {
            throw new BizException("订单已取消,座位已释放");
        }
        if (order.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException("订单已超时,座位已释放");
        }

        mockPaymentService.mockPayChannel(orderNo);

        // ★ CAS: 待支付 → 已支付(与超时关单互斥, 防双花)
        int updated = orderMapper.casMarkPaid(orderNo);
        if (updated == 0) {
            throw new BizException("订单状态已变化(可能已超时),请刷新");
        }

        // 锁 → 售
        List<Integer> seats = orderCore.seatIndexesOf(order);
        confirmPaidWithBitmapRecovery(order, seats);
        // 清理 user:pending 与 user:locked
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        orderCore.clearUserLockedHash(order.getUserId(), order.getSessionId());
        seatEventPublisher.publishSold(order.getSessionId(), seats);
        // D1 大屏: 推一条售出事件
        adminEventPublisher.publish("SOLD", Map.of(
                "orderNo", order.getOrderNo(),
                "userId", order.getUserId(),
                "sessionId", order.getSessionId(),
                "seats", seats,
                "amount", order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount()));
        // N2: 支付成功生成电子票
        try {
            ticketService.generate(orderNo);
        } catch (Exception e) {
            log.warn("[支付] orderNo={} 生成电子票失败(不影响支付结果), err={}", orderNo, e.toString());
        }
        log.info("[支付] orderNo={} seats={} 支付成功", orderNo, seats);
    }

    /**
     * N1 退票流程:
     * <ol>
     *   <li>校验: 订单属于本人 + status=PAID + 场次未开场</li>
     *   <li>CAS PAID → REFUNDING</li>
     *   <li>释放座位 (走 refund_seat.lua,清 sold + lock 位)</li>
     *   <li>调模拟退款</li>
     *   <li>写 refund_log</li>
     *   <li>CAS REFUNDING → REFUNDED</li>
     *   <li>清理缓存 + 广播 RELEASED</li>
     * </ol>
     */
    public void refund(String orderNo, Long userId) {
        Order order = orderCore.getOwnedOrder(orderNo, userId);
        if (order.getStatus() != OrderStatus.PAID.getCode()) {
            throw new BizException("仅已支付订单可退票,当前状态:" + OrderStatus.textOf(order.getStatus()));
        }
        Session session = sessionMapper.selectById(order.getSessionId());
        if (session == null) {
            throw new BizException("场次不存在");
        }
        if (!session.getStartTime().isAfter(LocalDateTime.now())) {
            throw new BizException("场次已开场,无法退票");
        }

        // CAS PAID → REFUNDING
        int updated = orderMapper.casMarkRefunding(orderNo);
        if (updated == 0) {
            throw new BizException("订单状态已变化,请刷新");
        }

        // 释放座位 (已售 → 可选)。P1-1 修复:必须用 refundSold 清 sold 位,
        // releaseUnpaid 只清未售座位,对已支付单(sold=1)一个位都不动 → 退票后座位永久不可售
        // 2026-10-24: 带 orderNo 走「按订单幂等」通道 —— 本单座位只会被释放一次。
        // 原因见 refund_seat.lua 顶部注释:释放后若本单卡在 REFUNDING,补偿任务会二次释放,
        // 那时座位若已被他人买走,第二次清位就会把别人已付款的座位清成可选 → 超卖。
        List<Integer> seats = orderCore.seatIndexesOf(order);
        List<Integer> releasedSeats = seatLuaService.refundSold(
                new SeatWrite.Paid(order.getSessionId(), seats, order.getOrderNo()));
        if (releasedSeats.size() != seats.size()) {
            // 位图与订单不一致(位图漂移):退票本身仍应完成,记录告警供管理端「手动恢复位图」兜底
            log.warn("[退票] orderNo={} 位图释放数量不符 期望={} 实际={} (可能存在历史位图漂移)",
                    orderNo, seats.size(), releasedSeats.size());
        }

        RefundLog refundLog = new RefundLog();
        refundLog.setId(IdWorker.getId());
        refundLog.setOrderNo(orderNo);
        refundLog.setUserId(userId);
        refundLog.setAmount(order.getTotalAmount());
        refundLog.setStatus(0);
        refundLog.setReason("用户主动退票");

        try {
            mockRefundService.mockRefundChannel(orderNo, order.getTotalAmount());
        } catch (Exception e) {
            // 模拟退款失败: 状态回滚到 REFUNDING, 等补偿任务兜底
            refundLog.setStatus(1);
            refundLog.setReason("渠道退款失败: " + e.getMessage());
            refundLogMapper.insert(refundLog);
            log.warn("[退票] orderNo={} 渠道退款失败, 等补偿任务", orderNo, e);
            throw new BizException("退款渠道异常,请稍后重试或联系客服");
        }
        refundLogMapper.insert(refundLog);

        // CAS REFUNDING → REFUNDED
        int finalUpdated = orderMapper.casMarkRefunded(orderNo);
        if (finalUpdated == 0) {
            // 理论上不会发生(只有退票流程推进 REFUNDING), 但兜底 log
            log.warn("[退票] orderNo={} CAS REFUNDING→REFUNDED 失败, 待补偿", orderNo);
        }

        // 清理缓存 + 广播
        redisTemplate.delete(RedisKeys.userPending(userId, order.getSessionId()));
        orderCore.clearUserLockedHash(userId, order.getSessionId());
        seatEventPublisher.publishReleased(order.getSessionId(), seats);
        // D1 大屏: 推一条退票事件
        adminEventPublisher.publish("REFUND", Map.of(
                "orderNo", order.getOrderNo(),
                "userId", userId,
                "sessionId", order.getSessionId(),
                "seats", seats,
                "amount", order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount()));
        log.info("[退票] orderNo={} seats={} 退款成功 amount={}", orderNo, seats, order.getTotalAmount());
    }
}

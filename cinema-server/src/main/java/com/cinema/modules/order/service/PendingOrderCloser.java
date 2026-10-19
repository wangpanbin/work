package com.cinema.modules.order.service;

import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.redis.SeatWrite;
import com.cinema.infra.ws.AdminEventPublisher;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.CloseReason;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 待支付订单的关闭协议 —— 唯一一处「怎么把一笔待支付单关干净」的实现。
 *
 * <p><b>为什么收拢</b>: 关单此前散在两处 ——
 * {@code OrderCancelService#closeOrder}(用户取消 / 超时扫描)与
 * {@code OrderLockService#closePending}(同场次重新锁座顶掉旧单)。
 * 两份实现的动作序列几乎一样, 只差两处: ① 后者不发管理端大屏事件;
 * ② 后者不关心 CAS 是否成功。改一处漏一处, 就会出现「某条路径没清缓存」
 * 或「顶替旧单被当成取消记进大屏流水」这类只在并发下才显形的偏差。
 *
 * <p><b>动作序列(顺序不可换)</b>:
 * <ol>
 *   <li><b>CAS</b> 推进状态 —— 只有 CAS 成功(0→2)才继续。并发重复投递天然安全。</li>
 *   <li>释放座位 —— 走 {@code release_seat.lua}(只清锁定位), 类型是
 *       {@link SeatWrite.Unpaid}; 已支付订单的座位要用 {@code refundSold}。</li>
 *   <li>清 {@code user:pending} 与 {@code user:locked} 缓存。</li>
 *   <li>发座位释放事件(RELEASED)—— 客户端据此把座位画回可选。</li>
 *   <li>按 {@link CloseReason} 决定是否发管理端大屏事件。</li>
 * </ol>
 *
 * <p><b>不变量</b>: 座位释放走的是「未支付」通道, 绝不能用退票通道 ——
 * 后者会连已售位一起清, 对一笔从未支付过的订单是错的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderCloser {

    private final OrderMapper orderMapper;
    private final SeatLuaService seatLuaService;
    private final SeatEventPublisher seatEventPublisher;
    private final AdminEventPublisher adminEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final OrderCore orderCore;

    /**
     * 关闭一笔待支付订单。
     *
     * @param order  待关的订单(调用方负责保证它当前是待支付态)
     * @param reason 关闭原因, 决定是否发管理端大屏事件
     * @return CAS 是否成功推进(失败说明订单已被别的流程改掉, 本次什么都没做)
     */
    public boolean close(Order order, CloseReason reason) {
        if (order == null) {
            return false;
        }
        int updated = orderMapper.casCancel(order.getOrderNo());
        if (updated == 0) {
            return false;
        }
        List<Integer> seats = orderCore.seatIndexesOf(order);
        List<Integer> released = seatLuaService.releaseUnpaid(
                new SeatWrite.Unpaid(order.getSessionId(), seats));
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        orderCore.clearUserLockedHash(order.getUserId(), order.getSessionId());
        seatEventPublisher.publishReleased(order.getSessionId(), seats);
        if (reason.publishesAdminEvent()) {
            adminEventPublisher.publish(reason.name(), Map.of(
                    "orderNo", order.getOrderNo(),
                    "userId", order.getUserId(),
                    "sessionId", order.getSessionId(),
                    "seats", seats));
        }
        log.info("[关单] orderNo={} type={} 释放座位 {}(实际释放 {})",
                order.getOrderNo(), reason.name(), seats, released);
        return true;
    }
}

package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 待支付订单查询 —— 「某用户在某场次是否已有一笔待支付单」的唯一定义。
 *
 * <p><b>为什么收拢</b>: 这条查询被两处用到, 口径必须一致 ——
 * ① 锁座前的幂等防重(有旧单就顶掉旧单再锁);
 * ② 对话助手的下单提案(要提前告诉用户「确认后旧单会被取消」, 见 ADR-0004 证据 1)。
 * 两处口径若不同, 用户会先看到「将被取消的订单号」, 确认后却发现取消的不是那一笔。
 *
 * <p><b>以 DB 为权威源</b>: Redis 的 {@code user:pending} 只作写入镜像, 从不被读取 ——
 * 详见 {@code RedisKeys#userPending} 的说明。DB 上还有唯一索引
 * {@code uk_user_pending(user_id, pending_key)} 兜底, 所以这里查得到就一定只有一笔。
 */
@Component
@RequiredArgsConstructor
public class PendingOrderQuery {

    private final OrderMapper orderMapper;

    /**
     * 查用户在某场次的待支付订单。
     *
     * @return 待支付订单; 没有则返回 {@code null}
     */
    public Order find(Long userId, Long sessionId) {
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getSessionId, sessionId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .last("LIMIT 1"));
    }
}

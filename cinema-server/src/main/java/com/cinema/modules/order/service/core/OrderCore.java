package com.cinema.modules.order.service.core;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.seat.reader.SessionInfoLoader;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单核心公共逻辑 — CAS 校验 / 订单归属 / 座位索引读取 / Redis user:locked 清理.
 * <p>E1 拆分: 从 OrderService 抽出共享代码,各 service 注入使用.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCore {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SessionMapper sessionMapper;
    private final StringRedisTemplate redisTemplate;
    private final SessionInfoLoader sessionInfoLoader;

    /**
     * 校验订单属于本人 + 存在,否则抛 BizException
     */
    public Order getOwnedOrder(String orderNo, Long userId) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该订单", null);
        }
        return order;
    }

    /**
     * 取订单座位索引: 优先 Order.seatIndexCache, 缺失再查 order_item
     */
    public List<Integer> seatIndexesOf(Order order) {
        if (order.getSeatIndexCache() != null) {
            return order.getSeatIndexCache();
        }
        return seatIndexesOf(order.getId());
    }

    public List<Integer> seatIndexesOf(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
    }

    /**
     * 校验场次可售 + 未开场
     */
    public Session requirePurchasableSession(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("场次不存在");
        }
        if (session.getStatus() == null || session.getStatus() != 1) {
            throw new BizException("场次当前不可购票");
        }
        if (!session.getStartTime().isAfter(LocalDateTime.now())) {
            throw new BizException("场次已开场,无法购票");
        }
        return session;
    }

    /**
     * 校验订单处于待支付状态
     */
    public void requirePending(Order order) {
        if (order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            throw new BizException("当前状态不可取消");
        }
    }

    /**
     * 校验座位索引落在此场次的真实座位范围内 —— 防越界 SETBIT 撑爆 Redis 位图.
     *
     * <p>P0 修复(2026-10-11 稳定性测试): {@code LockSeatsDTO} 只有 {@code @NotEmpty} +
     * {@code @Size(max=4)}, 上界取决于场次(1 号厅 96 座 / 2 号厅 140 座), 无法用常量注解表达。
     * 缺少这道校验时越界索引会一路进到 {@code lock_seat.lua} 的 {@code SETBIT}, 而 Redis bitmap
     * 会按 offset <b>立即分配内存且永不回收</b>:
     * <ul>
     *   <li>实测 offset=100000 → 位图 18B 撑到 12501B(694 倍), 公开的 {@code GET /seat-map}
     *       响应从 278B 放大到 16969B(61 倍)</li>
     *   <li>实测 offset=4000000 → 响应 666959B(2399 倍)</li>
     *   <li>SETBIT 硬上界 2^32-1 → 单个 key 分配 512MiB, 实测 Redis used_memory_peak 冲至 641MiB</li>
     * </ul>
     * 由于 {@code /api/auth/register} 公开、位图 key 无 TTL, 这是一个无门槛的远程内存耗尽路径。
     *
     * <p>上界取 {@link SessionInfoLoader} 的 seatCount(与 {@code GET /seat-map} 同一来源),
     * 保证"客户端看得到的座位"与"服务端允许锁的座位"完全一致。
     *
     * @throws BizException code=40001 座位索引越界
     */
    public void requireSeatsInRange(Long sessionId, List<Integer> seats) {
        int seatCount = sessionInfoLoader.load(sessionId).getSeatCount();
        if (seatCount <= 0) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(),
                    "场次座位数异常, 无法锁座(sessionId=" + sessionId + ", seatCount=" + seatCount + ")", null);
        }
        for (Integer idx : seats) {
            if (idx == null || idx < 0 || idx >= seatCount) {
                throw new BizException(ResultCode.BAD_REQUEST.getCode(),
                        "座位索引越界: " + idx + ", 本场次有效范围 [0, " + (seatCount - 1) + "]", null);
            }
        }
    }

    /**
     * 清理 user:locked 缓存,失败仅 log 不抛出
     */
    public void clearUserLockedHash(Long userId, Long sessionId) {
        try {
            redisTemplate.delete(RedisKeys.userLocked(userId, sessionId));
        } catch (Exception e) {
            log.warn("[关单/支付] 清理 user:locked 失败 uid={} sid={}", userId, sessionId, e);
        }
    }
}

package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.delay.DelayQueue;
import com.cinema.infra.redis.LuaLockResult;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.cinema.modules.order.vo.LockResultVO;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订单锁座下单 — OrderService 拆分的 4 个 service 之一.
 * <p>E1 拆分: 负责入场环节(锁座 → 建单 → 入延迟队列),与支付/取消解耦.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLockService {

    private static final long PAY_WINDOW_MILLIS = 15 * 60 * 1000L;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SessionMapper sessionMapper;
    private final SeatLuaService seatLuaService;
    private final SeatBitmapGuard seatBitmapGuard;
    private final DelayQueue delayQueue;
    private final SeatEventPublisher seatEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OrderCore orderCore;

    /** 锁座下单 */
    public LockResultVO lockSeats(Long userId, LockSeatsDTO dto) {
        Long sessionId = dto.getSessionId();
        List<Integer> seats = dto.getSeatIndexes().stream().distinct().sorted().toList();
        if (seats.isEmpty() || seats.size() > 4) {
            throw new BizException("每次最多选择4个座位");
        }

        Session session = orderCore.requirePurchasableSession(sessionId);

        // P5: 锁座前兜底, 若位图缺失则从 DB 重建(防服务重启后超卖)
        seatBitmapGuard.ensureBitmaps(sessionId);

        // 幂等防重: Redis 短路, 命中再去 DB 确认
        Order pending = findPendingOrderShortCircuit(userId, sessionId);
        if (pending != null) {
            orderCore.clearUserLockedHash(userId, sessionId);
            // 复用 OrderCore.getOwnedOrder 之外的快速关闭: 直接关闭旧单
            closePending(pending);
        }

        // ★ Redis Lua 原子锁座(防超卖核心)
        String lockKey = RedisKeys.sessionLock(sessionId);
        String soldKey = RedisKeys.sessionSold(sessionId);
        LuaLockResult lockResult = seatLuaService.lockSeats(lockKey, soldKey, seats);
        if (!lockResult.ok()) {
            throw new BizException(ResultCode.BUSINESS_ERROR.getCode(), "座位已被占用",
                    Map.of("conflict", lockResult.conflict()));
        }

        // 落库(事务), 失败补偿释放防"幽灵锁座"
        Order order;
        try {
            order = transactionTemplate.execute(tx -> createPendingOrder(userId, session, seats));
        } catch (DuplicateKeyException e) {
            seatLuaService.releaseSeats(lockKey, soldKey, seats);
            throw new BizException("操作太快啦,您在该场次已有待支付订单");
        } catch (Exception e) {
            seatLuaService.releaseSeats(lockKey, soldKey, seats);
            throw e;
        }
        if (order == null) {
            seatLuaService.releaseSeats(lockKey, soldKey, seats);
            throw new BizException("订单创建失败");
        }

        // 15分钟延迟关单(ZSet) + 待支付单号缓存
        delayQueue.offer(order.getOrderNo(), PAY_WINDOW_MILLIS);
        redisTemplate.opsForValue().set(RedisKeys.userPending(userId, sessionId),
                order.getOrderNo(), Duration.ofMillis(PAY_WINDOW_MILLIS));

        // 写我的待支付座位 Hash, 便于 seatMap 接口走 Redis
        try {
            var hashOps = redisTemplate.opsForHash();
            String lockedKey = RedisKeys.userLocked(userId, sessionId);
            for (Integer idx : seats) {
                hashOps.put(lockedKey, String.valueOf(idx), order.getOrderNo());
            }
            redisTemplate.expire(lockedKey, Duration.ofMillis(PAY_WINDOW_MILLIS));
        } catch (Exception e) {
            log.warn("[锁座] 写 user:locked 缓存失败 uid={} sid={}", userId, sessionId, e);
        }

        // 实时广播: 其他用户看到座位变灰
        seatEventPublisher.publishLocked(sessionId, seats);
        log.info("[锁座] user={} session={} seats={} orderNo={}", userId, sessionId, seats, order.getOrderNo());
        return new LockResultVO(order.getOrderNo(), order.getExpireAt(), order.getTotalAmount(), seats);
    }

    /**
     * 创建待支付订单(Phase B-③ 优化 + 死锁修复):
     * <p>原版整段事务包 Order + N*OrderItem, 在 uk_user_pending 生成列唯一索引上
     * 200 并发下产生 next-key lock 死锁. 本实现拆成两段事务:
     * <ol>
     *   <li>Tx1: 仅插入 Order 一行, 取 orderId, 立即提交释放 next-key lock</li>
     *   <li>Tx2: 单独事务循环插入 order_item(逐条), 失败时补偿删 Order</li>
     * </ol>
     */
    private Order createPendingOrder(Long userId, Session session, List<Integer> seats) {
        // Tx1: 单独事务插入 Order(雪花预生成, 立即提交)
        final Order order = new Order();
        order.setOrderNo(IdWorker.getIdStr());
        order.setUserId(userId);
        order.setSessionId(session.getId());
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setTotalAmount(session.getPrice().multiply(BigDecimal.valueOf(seats.size())));
        order.setSeatCount(seats.size());
        order.setExpireAt(LocalDateTime.now().plusNanos(PAY_WINDOW_MILLIS * 1_000_000));
        transactionTemplate.execute(tx -> {
            orderMapper.insert(order);
            return null;
        });
        if (order.getId() == null) {
            throw new BizException("订单创建失败");
        }
        Long orderId = order.getId();

        // Tx2: 单独事务循环插入 order_item
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                for (Integer seatIndex : seats) {
                    OrderItem item = new OrderItem();
                    item.setId(IdWorker.getId());
                    item.setOrderId(orderId);
                    item.setSessionId(session.getId());
                    item.setSeatIndex(seatIndex);
                    item.setPrice(session.getPrice());
                    orderItemMapper.insert(item);
                }
            });
        } catch (Exception e) {
            // 补偿: 删除主订单, 避免悬挂订单
            try {
                orderMapper.deleteById(orderId);
            } catch (Exception ignored) {
            }
            throw e;
        }

        // Phase B-⑦: 预填 seatIndexCache, 后续 closeOrder 复用
        order.setSeatIndexCache(new ArrayList<>(seats));
        return order;
    }

    /**
     * 锁座前 Redis 短路查待支付单
     */
    private Order findPendingOrderShortCircuit(Long userId, Long sessionId) {
        String pendingKey = RedisKeys.userPending(userId, sessionId);
        Boolean exists = redisTemplate.hasKey(pendingKey);
        if (!Boolean.TRUE.equals(exists)) {
            return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getSessionId, sessionId)
                    .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                    .last("LIMIT 1"));
        }
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getSessionId, sessionId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .last("LIMIT 1"));
    }

    /** 关闭待支付单(锁座前幂等防重时调用) */
    private void closePending(Order order) {
        // 简化: 委托 cancel service 风格的关闭, 但这里是锁座流程内部, 不抛错
        int updated = orderMapper.casCancel(order.getOrderNo());
        if (updated == 0) return;
        List<Integer> seats = orderCore.seatIndexesOf(order);
        seatLuaService.releaseSeats(
                RedisKeys.sessionLock(order.getSessionId()),
                RedisKeys.sessionSold(order.getSessionId()), seats);
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        orderCore.clearUserLockedHash(order.getUserId(), order.getSessionId());
        seatEventPublisher.publishReleased(order.getSessionId(), seats);
    }
}

package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.delay.DelayQueue;
import com.cinema.infra.redis.LuaLockResult;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.vo.LockResultVO;
import com.cinema.modules.order.vo.OrderVO;
import com.cinema.modules.payment.service.MockPaymentService;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.seat.service.SeatService;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订单核心链路: 锁座 → 支付/超时关单
 * 一致性防护: Lua原子锁座(防超卖) + 生成列唯一索引(防重复占座) + CAS状态迁移(支付/关单互斥)
 *           + 落库失败补偿释放(防幽灵锁座) + 消费幂等(关单CAS)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final long PAY_WINDOW_MILLIS = 15 * 60 * 1000L;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;
    private final SeatMapper seatMapper;
    private final SeatLuaService seatLuaService;
    private final DelayQueue delayQueue;
    private final SeatEventPublisher seatEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final MockPaymentService mockPaymentService;

    /** 锁座下单 */
    public LockResultVO lockSeats(Long userId, LockSeatsDTO dto) {
        Long sessionId = dto.getSessionId();
        List<Integer> seats = dto.getSeatIndexes().stream().distinct().sorted().toList();
        if (seats.isEmpty() || seats.size() > 4) {
            throw new BizException("每次最多选择4个座位");
        }

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

        // 幂等防重: 同场次已有待支付单 → 先走关单流程释放旧座位
        Order pending = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getSessionId, sessionId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .last("LIMIT 1"));
        if (pending != null) {
            closeOrder(pending);
        }

        // ★ Redis Lua 原子锁座(防超卖核心)
        String lockKey = RedisKeys.sessionLock(sessionId);
        String soldKey = RedisKeys.sessionSold(sessionId);
        LuaLockResult lockResult = seatLuaService.lockSeats(lockKey, soldKey, seats);
        if (!lockResult.ok()) {
            // 冲突座位回传前端, 便于自动刷新座位图
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

        // 实时广播: 其他用户看到座位变灰
        seatEventPublisher.publishLocked(sessionId, seats);
        log.info("[锁座] user={} session={} seats={} orderNo={}", userId, sessionId, seats, order.getOrderNo());
        return new LockResultVO(order.getOrderNo(), order.getExpireAt(), order.getTotalAmount(), seats);
    }

    private Order createPendingOrder(Long userId, Session session, List<Integer> seats) {
        Order order = new Order();
        order.setOrderNo(IdWorker.getIdStr());
        order.setUserId(userId);
        order.setSessionId(session.getId());
        order.setStatus(OrderStatus.PENDING_PAY.getCode());
        order.setTotalAmount(session.getPrice().multiply(BigDecimal.valueOf(seats.size())));
        order.setSeatCount(seats.size());
        order.setExpireAt(LocalDateTime.now().plusNanos(PAY_WINDOW_MILLIS * 1_000_000));
        orderMapper.insert(order);
        for (Integer seatIndex : seats) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setSessionId(session.getId());
            item.setSeatIndex(seatIndex);
            item.setPrice(session.getPrice());
            orderItemMapper.insert(item);
        }
        return order;
    }

    /** 模拟支付 */
    public void pay(String orderNo, Long userId) {
        Order order = getOwnedOrder(orderNo, userId);
        if (order.getStatus() == OrderStatus.PAID.getCode()) {
            throw new BizException("订单已支付,请勿重复操作");
        }
        if (order.getStatus() == OrderStatus.CANCELLED.getCode()) {
            throw new BizException("订单已取消,座位已释放");
        }
        if (order.getExpireAt().isBefore(LocalDateTime.now())) {
            closeOrder(order);
            throw new BizException("订单已超时,座位已释放");
        }

        mockPaymentService.mockPayChannel(orderNo);

        // ★ CAS: 待支付 → 已支付(与超时关单互斥, 防双花)
        int updated = orderMapper.casMarkPaid(orderNo);
        if (updated == 0) {
            throw new BizException("订单状态已变化(可能已超时),请刷新");
        }

        // 锁 → 售
        List<Integer> seats = seatIndexesOf(order.getId());
        seatLuaService.confirmSeats(RedisKeys.sessionLock(order.getSessionId()),
                RedisKeys.sessionSold(order.getSessionId()), seats);
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        seatEventPublisher.publishSold(order.getSessionId(), seats);
        log.info("[支付] orderNo={} seats={} 支付成功", orderNo, seats);
    }

    /** 主动取消 */
    public void cancel(String orderNo, Long userId) {
        Order order = getOwnedOrder(orderNo, userId);
        if (order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            throw new BizException("当前状态不可取消");
        }
        closeOrder(order);
    }

    /**
     * 幂等关单(供超时扫描/补偿任务复用): CAS(0→2) 成功才释放座位, 重复投递天然安全
     */
    public boolean closeIfUnpaid(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAY.getCode()) {
            return false;
        }
        return closeOrder(order);
    }

    private boolean closeOrder(Order order) {
        int updated = orderMapper.casCancel(order.getOrderNo());
        if (updated == 0) {
            return false; // 已支付/已取消 → 忽略
        }
        List<Integer> seats = seatIndexesOf(order.getId());
        List<Integer> released = seatLuaService.releaseSeats(
                RedisKeys.sessionLock(order.getSessionId()),
                RedisKeys.sessionSold(order.getSessionId()), seats);
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        seatEventPublisher.publishReleased(order.getSessionId(), seats);
        log.info("[关单] orderNo={} 释放座位 {}(实际释放 {})", order.getOrderNo(), seats, released);
        return true;
    }

    public Page<OrderVO> myOrders(Long userId, Integer status, int page, int size) {
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(status != null, Order::getStatus, status)
                        .orderByDesc(Order::getCreatedAt));
        Page<OrderVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(p.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    public OrderVO detail(String orderNo, Long userId) {
        return toVO(getOwnedOrder(orderNo, userId));
    }

    private Order getOwnedOrder(String orderNo, Long userId) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该订单", null);
        }
        return order;
    }

    private List<Integer> seatIndexesOf(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
    }

    private OrderVO toVO(Order order) {
        OrderVO vo = new OrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setSessionId(order.getSessionId());
        vo.setStatus(order.getStatus());
        vo.setStatusText(OrderStatus.textOf(order.getStatus()));
        vo.setTotalAmount(order.getTotalAmount());
        vo.setSeatCount(order.getSeatCount());
        vo.setExpireAt(order.getExpireAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setCreatedAt(order.getCreatedAt());

        List<Integer> seatIndexes = seatIndexesOf(order.getId());
        vo.setSeatIndexes(seatIndexes);

        Session session = sessionMapper.selectById(order.getSessionId());
        if (session != null) {
            Movie movie = movieMapper.selectById(session.getMovieId());
            Hall hall = hallMapper.selectById(session.getHallId());
            vo.setMovieTitle(movie == null ? "" : movie.getTitle());
            vo.setHallName(hall == null ? "" : hall.getName());
            vo.setStartTime(session.getStartTime());
            vo.setSeatDesc(buildSeatDesc(session, seatIndexes));
        }
        return vo;
    }

    private String buildSeatDesc(Session session, List<Integer> seatIndexes) {
        if (session == null || seatIndexes.isEmpty()) {
            return "";
        }
        return seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, session.getHallId())
                        .in(Seat::getSeatIndex, seatIndexes))
                .stream()
                .sorted(Comparator.comparing(Seat::getSeatIndex))
                .map(s -> s.getRowNo() + "排" + s.getColNo() + "座")
                .collect(Collectors.joining("、"));
    }
}

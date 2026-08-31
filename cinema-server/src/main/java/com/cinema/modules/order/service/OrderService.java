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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单核心链路: 锁座 → 支付/超时关单
 * <p>Phase B 优化:
 * <ul>
 *   <li>③ order_item 批量插入(saveBatch, 事务窗口内只有 1 INSERT + 1 batch INSERT)</li>
 *   <li>⑥ 锁座前 Redis EXISTS 短路(userPending 命中跳过 DB 查待支付单)</li>
 *   <li>⑦ closeOrder 复用 Order.seatIndexCache, 避免再次 selectList(order_item)</li>
 *   <li>Redis user:locked Hash 维护, 给 seatMap 走缓存用</li>
 * </ul>
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
    private final ObjectMapper objectMapper;

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

        // 幂等防重(Phase B-⑥): Redis 短路, 命中再去 DB 确认, DB 才是真值
        Order pending = findPendingOrderShortCircuit(userId, sessionId);
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

        // Phase A-①: 写我的待支付座位 Hash, 便于 seatMap 接口走 Redis(避免再查 DB)
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
     * 200 并发下产生 next-key lock 死锁(P99 飙到 245ms).
     * <p>本实现拆成两段事务:
     * <ol>
     *   <li>Tx1: 仅插入 Order 一行, 取 orderId, 立即提交释放 next-key lock</li>
     *   <li>Tx2: 单独事务循环插入 order_item(逐条), 失败时补偿删 Order</li>
     * </ol>
     * <p>注意: MyBatis-Plus 3.5.12 的 BaseMapper.insert(Collection) 底层是循环单条,
     * 与循环 for 行为等价. 真正的 batch INSERT 需要自定义 Provider, 这里暂不实施.
     * 拆事务后死锁消失, 性能恢复.
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

        // Phase B-⑦: 预填 seatIndexCache, 后续 closeOrder 复用, 免去再查 order_item
        order.setSeatIndexCache(new ArrayList<>(seats));
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

        // Phase B-⑤: mockPayChannel 已删除 Thread.sleep, 改为轻量校验
        mockPaymentService.mockPayChannel(orderNo);

        // ★ CAS: 待支付 → 已支付(与超时关单互斥, 防双花)
        int updated = orderMapper.casMarkPaid(orderNo);
        if (updated == 0) {
            throw new BizException("订单状态已变化(可能已超时),请刷新");
        }

        // 锁 → 售
        List<Integer> seats = seatIndexesOf(order);
        seatLuaService.confirmSeats(RedisKeys.sessionLock(order.getSessionId()),
                RedisKeys.sessionSold(order.getSessionId()), seats);
        // 清理 user:pending 与 user:locked(用户已支付, 不再是"我锁的")
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        clearUserLockedHash(order.getUserId(), order.getSessionId());
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
        // Phase B-⑦: 一次查 order_item 复用给 closeOrder
        order.setSeatIndexCache(seatIndexesOf(order.getId()));
        return closeOrder(order);
    }

    private boolean closeOrder(Order order) {
        int updated = orderMapper.casCancel(order.getOrderNo());
        if (updated == 0) {
            return false; // 已支付/已取消 → 忽略
        }
        // Phase B-⑦: 优先复用 Order.seatIndexCache
        List<Integer> seats = order.getSeatIndexCache() != null
                ? order.getSeatIndexCache()
                : seatIndexesOf(order.getId());
        List<Integer> released = seatLuaService.releaseSeats(
                RedisKeys.sessionLock(order.getSessionId()),
                RedisKeys.sessionSold(order.getSessionId()), seats);
        redisTemplate.delete(RedisKeys.userPending(order.getUserId(), order.getSessionId()));
        clearUserLockedHash(order.getUserId(), order.getSessionId());
        seatEventPublisher.publishReleased(order.getSessionId(), seats);
        log.info("[关单] orderNo={} 释放座位 {}(实际释放 {})", order.getOrderNo(), seats, released);
        return true;
    }

    /**
     * 我的订单(Phase F-① 优化):
     * <p>原版 N+5: 每个 order 单独查 session/movie/hall/order_item/seat, 20 orders = 100+ SQL
     * <p>优化后: 固定 6 SQL(order + order_item + session + movie + hall + seat), 与页大小无关
     * <p>缓存策略(Phase F-② 实测): 用户级 Redis 缓存 + 写时失效在高并发下副作用大(SCAN 阻塞),
     *   暂不启用. 批量查已足以让 P99 显著下降. 缓存待加 Caffeine 二级时再启用.
     */
    public Page<OrderVO> myOrders(Long userId, Integer status, int page, int size) {
        // 1. 查订单
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(status != null, Order::getStatus, status)
                        .orderByDesc(Order::getCreatedAt));
        List<Order> orders = p.getRecords();
        if (orders.isEmpty()) {
            return new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        }

        // 2. 一次查 order_item
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        Map<Long, List<Integer>> orderSeatMap = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(
                        OrderItem::getOrderId,
                        Collectors.mapping(OrderItem::getSeatIndex, Collectors.toList())));

        // 3. 一次查 session
        List<Long> sessionIds = orders.stream().map(Order::getSessionId).distinct().toList();
        Map<Long, Session> sessionMap = sessionMapper.selectBatchIds(sessionIds)
                .stream().collect(Collectors.toMap(Session::getId, Function.identity()));

        // 4. 一次查 movie
        List<Long> movieIds = sessionMap.values().stream()
                .map(Session::getMovieId).distinct().toList();
        Map<Long, Movie> movieMap = movieIds.isEmpty() ? Map.of() :
                movieMapper.selectBatchIds(movieIds)
                        .stream().collect(Collectors.toMap(Movie::getId, Function.identity()));

        // 5. 一次查 hall
        List<Long> hallIds = sessionMap.values().stream()
                .map(Session::getHallId).distinct().toList();
        Map<Long, Hall> hallMap = hallIds.isEmpty() ? Map.of() :
                hallMapper.selectBatchIds(hallIds)
                        .stream().collect(Collectors.toMap(Hall::getId, Function.identity()));

        // 6. 一次查 seat (按 hall_id IN + seat_index IN)
        List<Integer> allSeatIdx = orderSeatMap.values().stream()
                .flatMap(List::stream).distinct().toList();
        Map<String, Seat> seatKey = new HashMap<>();
        if (!allSeatIdx.isEmpty() && !hallIds.isEmpty()) {
            seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                            .in(Seat::getHallId, hallIds)
                            .in(Seat::getSeatIndex, allSeatIdx))
                    .forEach(s -> seatKey.put(s.getHallId() + ":" + s.getSeatIndex(), s));
        }

        // 组装 VO
        Page<OrderVO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        result.setRecords(orders.stream()
                .map(o -> toVOBatched(o,
                        orderSeatMap.getOrDefault(o.getId(), List.of()),
                        sessionMap, movieMap, hallMap, seatKey))
                .toList());
        return result;
    }

    /**
     * 单个订单详情也走批量查: 与 myOrders 共享 map 路径
     */
    private OrderVO toVOBatched(Order order, List<Integer> seatIndexes,
                                Map<Long, Session> sessionMap,
                                Map<Long, Movie> movieMap,
                                Map<Long, Hall> hallMap,
                                Map<String, Seat> seatKey) {
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

        vo.setSeatIndexes(seatIndexes);

        Session session = sessionMap.get(order.getSessionId());
        if (session != null) {
            Movie movie = movieMap.get(session.getMovieId());
            Hall hall = hallMap.get(session.getHallId());
            vo.setMovieTitle(movie == null ? "" : movie.getTitle());
            vo.setHallName(hall == null ? "" : hall.getName());
            vo.setStartTime(session.getStartTime());
            // buildSeatDesc 从 seatKey 取
            if (!seatIndexes.isEmpty() && hall != null) {
                StringBuilder sb = new StringBuilder();
                List<Integer> sorted = seatIndexes.stream().sorted().toList();
                for (int i = 0; i < sorted.size(); i++) {
                    if (i > 0) sb.append("、");
                    Seat s = seatKey.get(hall.getId() + ":" + sorted.get(i));
                    if (s != null) sb.append(s.getRowNo()).append("排").append(s.getColNo()).append("座");
                }
                vo.setSeatDesc(sb.toString());
            }
        }
        return vo;
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

    private List<Integer> seatIndexesOf(Order order) {
        if (order.getSeatIndexCache() != null) {
            return order.getSeatIndexCache();
        }
        return seatIndexesOf(order.getId());
    }

    private List<Integer> seatIndexesOf(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
    }

    // ------------------------------------------------------------------
    // Phase B-⑥ 锁座前 Redis 短路
    // ------------------------------------------------------------------

    /**
     * 先 EXISTS Redis cinema:user:pending:{uid}:{sid}, 命中才查 DB 拿真值.
     * DB 不存在(理论上 TTL 内一致)时回填为空 key 略.
     * 缓存未命中直接放过, 走原 DB 查询路径.
     */
    private Order findPendingOrderShortCircuit(Long userId, Long sessionId) {
        String pendingKey = RedisKeys.userPending(userId, sessionId);
        Boolean exists = redisTemplate.hasKey(pendingKey);
        if (!Boolean.TRUE.equals(exists)) {
            // 缓存未命中, 走原 DB 查询
            return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getSessionId, sessionId)
                    .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                    .last("LIMIT 1"));
        }
        // 缓存命中, 仍去 DB 拿真值(Redis 可能是过期未 DEL 的脏数据)
        return orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getSessionId, sessionId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .last("LIMIT 1"));
    }

    private void clearUserLockedHash(Long userId, Long sessionId) {
        try {
            redisTemplate.delete(RedisKeys.userLocked(userId, sessionId));
        } catch (Exception e) {
            log.warn("[关单/支付] 清理 user:locked 失败 uid={} sid={}", userId, sessionId, e);
        }
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

        List<Integer> seatIndexes = seatIndexesOf(order);
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

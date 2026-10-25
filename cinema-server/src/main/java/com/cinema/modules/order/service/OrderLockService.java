package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.infra.delay.DelayQueue;
import com.cinema.infra.redis.LuaLockResult;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.SeatBitmapGuard;
import com.cinema.infra.redis.SeatLuaService;
import com.cinema.infra.redis.SeatWrite;
import com.cinema.infra.ws.AdminEventPublisher;
import com.cinema.infra.ws.SeatEventPublisher;
import com.cinema.modules.order.dto.LockSeatsDTO;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.CloseReason;
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
 * 订单锁座下单 — OrderService 拆分的 4 个 service 之一。
 * <p>E1 拆分: 负责入场环节(锁座 → 建单 → 入延迟队列),与支付/取消解耦。
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
    private final AdminEventPublisher adminEventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OrderCore orderCore;
    private final PendingOrderQuery pendingOrderQuery;
    private final PendingOrderCloser pendingOrderCloser;

    /** 锁座下单 */
    public LockResultVO lockSeats(Long userId, LockSeatsDTO dto) {
        Long sessionId = dto.getSessionId();
        // P0 修复(2026-10-11): 过滤 null 后再去重排序, 避免 stream.sorted() 对 null 抛 NPE。
        // DTO 上已加 @NotNull 元素约束, 这里是"绕过 Controller 直接调 service"时的兜底。
        List<Integer> seats = dto.getSeatIndexes().stream()
                .filter(java.util.Objects::nonNull)
                .distinct().sorted().toList();
        if (seats.isEmpty() || seats.size() > 4) {
            throw new BizException("每次最多选择4个座位");
        }

        Session session = orderCore.requirePurchasableSession(sessionId);

        // P0 修复(2026-10-11 稳定性测试): 座位索引必须落在本场次真实范围内。
        // 越界索引会进到 lock_seat.lua 的 SETBIT, 让 Redis 位图按 offset 立即分配内存且永不回收
        // (实测 offset=100000 即 694 倍膨胀; 上界 2^32-1 时单 key 512MiB)。详见 OrderCore#requireSeatsInRange。
        orderCore.requireSeatsInRange(sessionId, seats);

        // P5: 锁座前兜底, 若位图缺失则从 DB 重建(防服务重启后超卖)
        seatBitmapGuard.ensureBitmaps(sessionId);

        // 幂等防重: 查用户当前 PENDING_PAY 订单; Redis user:pending 仅作为 lockSeats 写入提示,
        // 实际查询以 DB 为权威源(Redis value 在本仓库从未被读取,见 #5 收尾)。
        //
        // P0-2 修复(2026-10-25): 同场次重复锁座时,「关旧单」与「锁新座」的先后顺序
        // 决定了失败时用户是否两头落空。规格:
        //   ① 新旧座位**无交集**(用户改选别的座位) → 先锁新座, 锁成功后才关旧单。
        //      锁失败 → 旧单原样保留, 用户手上仍有一笔可支付的单。
        //   ② 新旧座位**有交集**(用户重选了自己已锁的座位) → 保持原顺序(先关旧单释放座位, 再锁新座),
        //      因为旧单的锁还在位图上, 不先释放就必然与自己冲突。
        //
        // DB 上有唯一索引 uk_user_pending(user_id, pending_key) —— 同一用户同一场次只能有一笔待支付单,
        // 所以两条路径都必须保证「关旧单」发生在「写新订单」之前。
        Order pending = findUserPendingOrder(userId, sessionId);
        List<Integer> pendingSeats = pending == null ? List.of() : orderCore.seatIndexesOf(pending);
        boolean sharesSeat = pending != null && seats.stream().anyMatch(pendingSeats::contains);

        if (pending != null && sharesSeat) {
            orderCore.clearUserLockedHash(userId, sessionId);
            // 复用 OrderCore.getOwnedOrder 之外的快速关闭: 直接关闭旧单
            closePending(pending);
            // ★ Redis Lua 原子锁座(防超卖核心)
            lockSeatsOrThrow(sessionId, seats);
        } else {
            // 无交集(或没有旧单): 先锁新座 —— 锁失败时旧单一个字都不动, 用户不会两头落空
            lockSeatsOrThrow(sessionId, seats);
            if (pending != null) {
                // 锁成功后才动旧单, 且必须在写新订单之前关掉(uk_user_pending 唯一索引)
                orderCore.clearUserLockedHash(userId, sessionId);
                closePendingReplacing(sessionId, seats, pending);
            }
        }

        // 落库(事务), 失败补偿释放防"幽灵锁座"
        Order order;
        try {
            order = transactionTemplate.execute(tx -> createPendingOrder(userId, session, seats));
        } catch (DuplicateKeyException e) {
            seatLuaService.releaseUnpaid(new SeatWrite.Unpaid(sessionId, seats));
            throw new BizException("操作太快啦,您在该场次已有待支付订单");
        } catch (Exception e) {
            seatLuaService.releaseUnpaid(new SeatWrite.Unpaid(sessionId, seats));
            throw e;
        }
        if (order == null) {
            seatLuaService.releaseUnpaid(new SeatWrite.Unpaid(sessionId, seats));
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
        // D1 大屏: 推一条锁座事件
        adminEventPublisher.publish("LOCK", java.util.Map.of(
                "orderNo", order.getOrderNo(),
                "userId", userId,
                "sessionId", sessionId,
                "seats", seats));
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
     * 查用户在某场次的 PENDING_PAY 订单(锁座前幂等防重)。
     * <p>Redis user:pending 仅作为 lockSeats 写入提示; 本查询以 DB 为权威源
     * (Redis value 在本仓库从未被读取, 原 findPendingOrderShortCircuit 的 hasKey 分支是死分支, 见 #5 收尾)。
     *
     * <p><b>public 的原因</b>(ADR-0004):对话助手的下单提案在生成时也要知道
     * 「同场次是否已有待支付单」—— 确认后那笔会被 {@link #closePending} 取消,
     * 必须提前告知用户。复用同一个查询, 避免两处口径不一致。
     */
    public Order findUserPendingOrder(Long userId, Long sessionId) {
        return pendingOrderQuery.find(userId, sessionId);
    }

    /**
     * 关闭待支付单(锁座前幂等防重时调用)。
     *
     * <p>关单协议在 {@link PendingOrderCloser}, 这里只声明原因 ——
     * {@link CloseReason#REPLACED} 表示「旧单被同场次的新锁座顶掉」。
     * 座位释放事件照发(座位确实回到可选池), 但**不发管理端大屏事件**:
     * 顶替不是一次取消, 记进大屏流水会重复计数。
     *
     * @return CAS 是否成功推进(旧单已经不是待支付态时返回 false, 详见 {@link PendingOrderCloser#close})
     */
    private boolean closePending(Order order) {
        return pendingOrderCloser.close(order, CloseReason.REPLACED);
    }

    /**
     * 新座位**已锁成功**之后关闭旧单 —— 顺序不能反(见 lockSeats 里的 P0-2 说明)。
     *
     * <p>失败处理(防止幽灵锁座):
     * <ul>
     *   <li>关单抛异常(CAS 已提交但座位没释放 / DB 报错) → 新座位不能留成幽灵锁 →
     *       补偿释放后原样抛出。此时旧单要么仍是待支付(用户仍有一笔单),
     *       要么已被取消(用户看到错误提示重来)。</li>
     *   <li>CAS 没推进(close 返回 false) → 旧单已经不是待支付态(被并发关掉 / 已支付) →
     *       它在 uk_user_pending 上的 pending_key 已是 NULL, 不会与新单冲突, 继续建新单即可。</li>
     * </ul>
     */
    private void closePendingReplacing(Long sessionId, List<Integer> newSeats, Order pending) {
        boolean closed;
        try {
            closed = closePending(pending);
        } catch (RuntimeException e) {
            releaseNewSeatsQuietly(sessionId, newSeats);
            throw e;
        }
        if (!closed) {
            log.info("[锁座] 旧待支付单 {} 状态已变更(并发关单/已支付), 不重复关",
                    pending.getOrderNo());
        }
    }

    /**
     * 补偿释放刚锁上的新座位 —— 避免「幽灵锁座」(位图占着但没有订单持有)。
     *
     * <p>只清 lock 位、不动 sold 位(未支付通道), 与 {@link SeatLuaService#releaseUnpaid} 语义一致。
     */
    private void releaseNewSeatsQuietly(Long sessionId, List<Integer> seats) {
        try {
            seatLuaService.releaseUnpaid(new SeatWrite.Unpaid(sessionId, seats));
        } catch (Exception e) {
            log.error("[锁座] 补偿释放新座位失败 sid={} seats={}", sessionId, seats, e);
        }
    }

    /**
     * ★ Redis Lua 原子锁座(防超卖核心) —— 冲突直接抛业务异常。
     *
     * @throws BizException code=40002 座位已被占用(data 里带冲突座位列表)
     */
    private void lockSeatsOrThrow(Long sessionId, List<Integer> seats) {
        LuaLockResult lockResult = seatLuaService.lock(sessionId, seats);
        if (!lockResult.ok()) {
            throw new BizException(ResultCode.BUSINESS_ERROR.getCode(), "座位已被占用",
                    Map.of("conflict", lockResult.conflict()));
        }
    }
}

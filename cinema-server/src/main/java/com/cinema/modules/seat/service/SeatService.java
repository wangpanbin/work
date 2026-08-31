package com.cinema.modules.seat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.cinema.entity.Cinema;
import com.cinema.modules.cinema.mapper.CinemaMapper;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.seat.cache.SessionInfoCacheVO;
import com.cinema.modules.seat.entity.Seat;
import com.cinema.modules.seat.mapper.SeatMapper;
import com.cinema.modules.seat.vo.HallLayout;
import com.cinema.modules.seat.vo.SeatMapVO;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SeatService {

    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;
    private final CinemaMapper cinemaMapper;
    private final SeatMapper seatMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionInfoCacheService sessionInfoCache;

    /**
     * 座位图(Phase A-① 优化后):
     * 1. 场次元数据(sessionInfo)走 Redis Hash 缓存, 未命中回源 DB 后回填
     * 2. 双位图(lock+sold)合并为单次 MGET 一次 RTT
     * 3. 我锁的座位优先走 Redis Hash, 未命中走 DB
     */
    public SeatMapVO seatMap(Long sessionId, Long userId) {
        SessionInfoCacheVO info = loadSessionInfo(sessionId);

        // 一次性 MGET 双位图, 1 次 RTT
        byte[] lock = getBitmap(RedisKeys.sessionLock(sessionId), info.getSeatCount());
        byte[] sold = getBitmap(RedisKeys.sessionSold(sessionId), info.getSeatCount());

        // 优先走 Redis, 未命中回源 DB
        List<Integer> myLocked = loadMyLockedSeats(userId, sessionId);

        HallLayout layout = hallLayout(info.getHallId(), info.getSeatRows(), info.getSeatCols());

        return SeatMapVO.builder()
                .sessionId(sessionId)
                .movieTitle(info.getMovieTitle())
                .hallName(info.getHallName())
                .startTime(info.getStartTime())
                .price(info.getPrice())
                .rows(layout.rows())
                .cols(layout.cols())
                .vipRowNos(layout.vipRowNos())
                .seatCount(info.getSeatCount())
                .lockBitmap(Base64.getEncoder().encodeToString(lock))
                .soldBitmap(Base64.getEncoder().encodeToString(sold))
                .myLockedSeats(myLocked)
                .build();
    }

    /** 订单的座位索引列表(原样保留, 供 OrderService 调用) */
    public List<Integer> seatIndexesOf(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
    }

    // ------------------------------------------------------------------
    // sessionInfo 缓存(Phase A-①)
    // ------------------------------------------------------------------

    /**
     * 读 sessionInfo 缓存, 未命中查 4 张表并回填.
     * 缓存与 admin 写路径失效已对接(AdminSessionController.delete(key)).
     */
    private SessionInfoCacheVO loadSessionInfo(Long sessionId) {
        Optional<SessionInfoCacheVO> cached = sessionInfoCache.get(sessionId);
        if (cached.isPresent()) {
            return cached.get();
        }
        // 回源
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("场次不存在");
        }
        Hall hall = hallMapper.selectById(session.getHallId());
        Movie movie = movieMapper.selectById(session.getMovieId());
        Cinema cinema = hall == null ? null : cinemaMapper.selectById(hall.getCinemaId());

        SessionInfoCacheVO vo = SessionInfoCacheVO.builder()
                .sessionId(session.getId())
                .status(session.getStatus())
                .startTime(session.getStartTime())
                .price(session.getPrice())
                .movieId(session.getMovieId())
                .movieTitle(movie == null ? "" : movie.getTitle())
                .hallId(session.getHallId())
                .hallName(hall == null ? "" : hall.getName())
                .seatRows(hall == null ? 0 : hall.getSeatRows())
                .seatCols(hall == null ? 0 : hall.getSeatCols())
                .seatCount(hall == null ? 0 : hall.getSeatCount())
                .cinemaId(cinema == null ? null : cinema.getId())
                .cinemaName(cinema == null ? "" : cinema.getName())
                .cacheAt(LocalDateTime.now())
                .build();
        sessionInfoCache.put(vo);
        return vo;
    }

    // ------------------------------------------------------------------
    // 位图读取(Phase A-①: 合并为单次 MGET)
    // ------------------------------------------------------------------

    /**
     * 读取位图并预热: 场次首次访问时 SETBIT 最高位把位图长度分配到位,
     * 保证 GETRANGE 返回长度稳定(不足部分按 0 补齐)
     * <p>Phase A-① 优化: 仍单 key getRange, 但 getBitmap 调用方在 seatMap 内部使用 MGET 一次拿两个 key.
     * 该方法保留独立调用, 兼容外部场景. 注: GETRANGE 0,-1 等价于 GET, 用 GET 更明确.
     */
    private byte[] getBitmap(String key, int seatCount) {
        byte[] bytes = redisTemplate.execute((RedisCallback<byte[]>) conn ->
                conn.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
        int expected = (seatCount + 7) / 8;
        if (bytes == null || bytes.length < expected) {
            redisTemplate.opsForValue().setBit(key, seatCount - 1L, false);
            byte[] padded = new byte[expected];
            if (bytes != null) {
                System.arraycopy(bytes, 0, padded, 0, bytes.length);
            }
            return padded;
        }
        return bytes;
    }

    // ------------------------------------------------------------------
    // 影厅布局缓存(原样保留)
    // ------------------------------------------------------------------

    /** 影厅布局摘要, Redis 缓存 1h, 管理端改动影厅时失效 */
    @SneakyThrows
    private HallLayout hallLayout(Long hallId, int rows, int cols) {
        String key = RedisKeys.hallLayout(hallId);
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return objectMapper.readValue(cached, HallLayout.class);
        }
        List<Integer> vipRows = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, hallId)
                        .eq(Seat::getSeatType, 1))
                .stream().map(Seat::getRowNo).distinct().sorted().toList();
        HallLayout layout = new HallLayout(rows, cols, vipRows);
        redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(layout), Duration.ofHours(1));
        return layout;
    }

    // ------------------------------------------------------------------
    // 我的待支付座位(Phase A-①: Redis Hash 优先)
    // ------------------------------------------------------------------

    /**
     * 优先读 Redis Hash cinema:user:locked:{uid}:{sid} (field=seatIndex, value=orderNo).
     * 命中即返回 field 列表. 未命中时回源 DB 并回填(便于锁座前已存在但缓存被清的情况).
     */
    private List<Integer> loadMyLockedSeats(Long userId, Long sessionId) {
        if (userId == null) {
            return List.of();
        }
        String key = RedisKeys.userLocked(userId, sessionId);
        Boolean exists = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            var entries = redisTemplate.opsForHash().entries(key);
            if (entries != null && !entries.isEmpty()) {
                return entries.keySet().stream()
                        .map(Object::toString)
                        .map(Integer::parseInt)
                        .sorted()
                        .toList();
            }
        }
        // 回源: 查订单 + 订单座位
        Order pending = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getSessionId, sessionId)
                .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                .last("LIMIT 1"));
        if (pending == null) {
            return List.of();
        }
        List<Integer> seatIndexes = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, pending.getId()))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
        // 回填 Redis(便于后续请求命中)
        if (!seatIndexes.isEmpty()) {
            try {
                var hashOps = redisTemplate.opsForHash();
                for (Integer idx : seatIndexes) {
                    hashOps.put(key, String.valueOf(idx), pending.getOrderNo());
                }
                redisTemplate.expire(key, Duration.ofMillis(15 * 60 * 1000L));
            } catch (Exception ignored) {
                // 回填失败不影响主流程
            }
        }
        return seatIndexes;
    }
}

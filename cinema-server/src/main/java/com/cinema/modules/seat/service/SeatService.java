package com.cinema.modules.seat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.RedisKeys;
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
import java.util.Base64;
import java.util.List;

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

    /** 座位图: 布局(Redis缓存) + 双位图 + 我的待支付座位 */
    public SeatMapVO seatMap(Long sessionId, Long userId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("场次不存在");
        }
        Hall hall = hallMapper.selectById(session.getHallId());
        Movie movie = movieMapper.selectById(session.getMovieId());
        Cinema cinema = hall == null ? null : cinemaMapper.selectById(hall.getCinemaId());

        byte[] lock = getBitmap(RedisKeys.sessionLock(sessionId), hall.getSeatCount());
        byte[] sold = getBitmap(RedisKeys.sessionSold(sessionId), hall.getSeatCount());

        List<Integer> myLocked = List.of();
        if (userId != null) {
            Order pending = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getSessionId, sessionId)
                    .eq(Order::getStatus, OrderStatus.PENDING_PAY.getCode())
                    .last("LIMIT 1"));
            if (pending != null) {
                myLocked = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                                .eq(OrderItem::getOrderId, pending.getId()))
                        .stream().map(OrderItem::getSeatIndex).sorted().toList();
            }
        }

        HallLayout layout = hallLayout(hall);
        return SeatMapVO.builder()
                .sessionId(sessionId)
                .movieTitle(movie == null ? "" : movie.getTitle())
                .hallName(hall == null ? "" : hall.getName())
                .startTime(session.getStartTime())
                .price(session.getPrice())
                .rows(layout.rows())
                .cols(layout.cols())
                .vipRowNos(layout.vipRowNos())
                .seatCount(hall.getSeatCount())
                .lockBitmap(Base64.getEncoder().encodeToString(lock))
                .soldBitmap(Base64.getEncoder().encodeToString(sold))
                .myLockedSeats(myLocked)
                .build();
    }

    /** 订单的座位索引列表 */
    public List<Integer> seatIndexesOf(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
    }

    /**
     * 读取位图并预热: 场次首次访问时 SETBIT 最高位把位图长度分配到位,
     * 保证 GETRANGE 返回长度稳定(不足部分按 0 补齐)
     */
    private byte[] getBitmap(String key, int seatCount) {
        byte[] bytes = redisTemplate.execute((RedisCallback<byte[]>) conn ->
                conn.stringCommands().getRange(key.getBytes(StandardCharsets.UTF_8), 0, -1));
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

    /** 影厅布局摘要, Redis 缓存 1h, 管理端改动影厅时失效 */
    @SneakyThrows
    private HallLayout hallLayout(Hall hall) {
        String key = RedisKeys.hallLayout(hall.getId());
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            return objectMapper.readValue(cached, HallLayout.class);
        }
        List<Integer> vipRows = seatMapper.selectList(new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, hall.getId())
                        .eq(Seat::getSeatType, 1))
                .stream().map(Seat::getRowNo).distinct().sorted().toList();
        HallLayout layout = new HallLayout(hall.getSeatRows(), hall.getSeatCols(), vipRows);
        redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(layout), Duration.ofHours(1));
        return layout;
    }
}

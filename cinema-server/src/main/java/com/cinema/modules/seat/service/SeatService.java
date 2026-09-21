package com.cinema.modules.seat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.order.entity.OrderItem;
import com.cinema.modules.order.mapper.OrderItemMapper;
import com.cinema.modules.seat.cache.SessionInfoCacheVO;
import com.cinema.modules.seat.reader.HallLayoutCache;
import com.cinema.modules.seat.reader.MyLockedSeatsReader;
import com.cinema.modules.seat.reader.SessionBitmapReader;
import com.cinema.modules.seat.reader.SessionInfoLoader;
import com.cinema.modules.seat.vo.HallLayout;
import com.cinema.modules.seat.vo.SeatMapVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * 座位图服务(顶层 orchestrator).
 *
 * <p>#4 收尾: 原 234 行 god module 拆为 4 个 reader + 本 orchestrator.
 * <ul>
 *   <li>{@link SessionInfoLoader} — 缓存 + 4 表 join 回源</li>
 *   <li>{@link SessionBitmapReader} — Redis bitmap 读 + SETBIT 长度分配</li>
 *   <li>{@link HallLayoutCache} — Redis JSON 缓存 + DB 回填</li>
 *   <li>{@link MyLockedSeatsReader} — Redis Hash 优先 + DB 回填</li>
 * </ul>
 *
 * <p>本类只持有 4 个 reader + orderItemMapper, 字段从 10 → 5, seatMap() 缩成 5 行组合.
 */
@Service
@RequiredArgsConstructor
public class SeatService {

    private final SessionInfoLoader sessionInfoLoader;
    private final SessionBitmapReader bitmapReader;
    private final HallLayoutCache hallLayoutCache;
    private final MyLockedSeatsReader myLockedSeatsReader;
    private final OrderItemMapper orderItemMapper;

    /**
     * 座位图(Phase A-① 优化后):
     * 1. 场次元数据走 Redis Hash 缓存
     * 2. 双位图各自走 SessionBitmapReader
     * 3. 我锁的座位走 MyLockedSeatsReader
     */
    public SeatMapVO seatMap(Long sessionId, Long userId) {
        SessionInfoCacheVO info = sessionInfoLoader.load(sessionId);

        byte[] lock = bitmapReader.readWithPadding(RedisKeys.sessionLock(sessionId), info.getSeatCount());
        byte[] sold = bitmapReader.readWithPadding(RedisKeys.sessionSold(sessionId), info.getSeatCount());

        List<Integer> myLocked = myLockedSeatsReader.load(userId, sessionId);
        HallLayout layout = hallLayoutCache.load(info.getHallId(), info.getSeatRows(), info.getSeatCols());

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

    /** 订单的座位索引列表(供 OrderService 调用) */
    public List<Integer> seatIndexesOf(Long orderId) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, orderId))
                .stream().map(OrderItem::getSeatIndex).sorted().toList();
    }
}
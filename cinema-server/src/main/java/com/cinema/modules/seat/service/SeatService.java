package com.cinema.modules.seat.service;

import com.cinema.infra.redis.RedisKeys;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.service.PendingOrderQuery;
import com.cinema.modules.seat.cache.SessionInfoCacheVO;
import com.cinema.modules.seat.reader.HallLayoutCache;
import com.cinema.modules.seat.reader.MyLockedSeatsReader;
import com.cinema.modules.seat.reader.SessionBitmapReader;
import com.cinema.modules.seat.reader.SessionInfoLoader;
import com.cinema.modules.seat.vo.HallLayout;
import com.cinema.modules.seat.vo.SeatMapVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.Base64;
import java.util.List;

/**
 * 座位图服务(顶层 orchestrator)。
 *
 * <p>#4 收尾: 原 234 行 god module 拆为 4 个 reader + 本 orchestrator。
 * <ul>
 *   <li>{@link SessionInfoLoader} — 缓存 + 4 表 join 回源</li>
 *   <li>{@link SessionBitmapReader} — Redis bitmap 读 + SETBIT 长度分配</li>
 *   <li>{@link HallLayoutCache} — Redis JSON 缓存 + DB 回填</li>
 *   <li>{@link MyLockedSeatsReader} — Redis Hash 优先 + DB 回填</li>
 * </ul>
 *
 * <p>本类只持有 4 个 reader, 字段从 10 → 4, seatMap() 缩成 5 行组合。
 */
@Service
@RequiredArgsConstructor
public class SeatService {

    /**
     * 影院时区 —— 与 {@code OrderProposalService} 的 pendingCancelExpireAt 口径完全一致。
     * 两处必须同口径, 否则前端拿到的两个「待支付单过期时间」会对不上。
     */
    private static final ZoneId CINEMA_ZONE = ZoneId.of("Asia/Shanghai");

    private final SessionInfoLoader sessionInfoLoader;
    private final SessionBitmapReader bitmapReader;
    private final HallLayoutCache hallLayoutCache;
    private final MyLockedSeatsReader myLockedSeatsReader;

    /**
     * 待支付单查询 —— 直接注入 {@link PendingOrderQuery} 而不注入
     * {@code OrderLockService}: 前者只依赖 mapper, 不构成 Spring 循环依赖;
     * 后者会把整个锁座服务链(SeatLuaService / DelayQueue / TransactionTemplate)拖进座位模块。
     * 查询口径本来就收在 PendingOrderQuery 一处(见其类注释), 复用即可。
     */
    private final PendingOrderQuery pendingOrderQuery;

    /**
     * 座位图(Phase A-① 优化后):
     * 1. 场次元数据走 Redis Hash 缓存
     * 2. 双位图各自走 SessionBitmapReader
     * 3. 我锁的座位走 MyLockedSeatsReader
     * 4. 存量待支付单走 PendingOrderQuery(P0-2, 供前端提示「重选会取消旧单」)
     */
    public SeatMapVO seatMap(Long sessionId, Long userId) {
        SessionInfoCacheVO info = sessionInfoLoader.load(sessionId);

        byte[] lock = bitmapReader.readWithPadding(RedisKeys.sessionLock(sessionId), info.getSeatCount());
        byte[] sold = bitmapReader.readWithPadding(RedisKeys.sessionSold(sessionId), info.getSeatCount());

        List<Integer> myLocked = myLockedSeatsReader.load(userId, sessionId);
        HallLayout layout = hallLayoutCache.load(info.getHallId(), info.getSeatRows(), info.getSeatCols());

        // P0-2: 当前用户在该场次的存量待支付单(未登录时 userId 为 null → 跳过查询)
        Order pending = userId == null ? null : pendingOrderQuery.find(userId, sessionId);
        String pendingOrderNo = pending == null ? null : pending.getOrderNo();
        Long pendingExpireAt = (pending == null || pending.getExpireAt() == null)
                ? null
                : pending.getExpireAt().atZone(CINEMA_ZONE).toInstant().toEpochMilli();

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
                .pendingOrderNo(pendingOrderNo)
                .pendingExpireAt(pendingExpireAt)
                .build();
    }
}
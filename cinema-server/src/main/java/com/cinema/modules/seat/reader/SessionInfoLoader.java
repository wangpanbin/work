package com.cinema.modules.seat.reader;

import com.cinema.common.exception.BizException;
import com.cinema.infra.redis.cache.SessionInfoCacheService;
import com.cinema.modules.cinema.entity.Cinema;
import com.cinema.modules.cinema.mapper.CinemaMapper;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.movie.entity.Movie;
import com.cinema.modules.movie.mapper.MovieMapper;
import com.cinema.modules.seat.cache.SessionInfoCacheVO;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 场次元数据读侧: 缓存 hit 直接返; miss 时 join session/movie/hall/cinema 4 表回填.
 *
 * <p>#4 收尾: 抽自 SeatService.loadSessionInfo. Cache 协议由 SessionInfoCacheService 维护,
 * 本类只关心"读 + miss 时回源 DB".
 */
@Component
@RequiredArgsConstructor
public class SessionInfoLoader {

    private final SessionInfoCacheService sessionInfoCache;
    private final SessionMapper sessionMapper;
    private final MovieMapper movieMapper;
    private final HallMapper hallMapper;
    private final CinemaMapper cinemaMapper;

    /**
     * @throws BizException 当 session 不存在时
     */
    public SessionInfoCacheVO load(Long sessionId) {
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
}
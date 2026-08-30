package com.cinema.modules.session.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.exception.BizException;
import com.cinema.modules.cinema.entity.Cinema;
import com.cinema.modules.cinema.mapper.CinemaMapper;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.hall.mapper.HallMapper;
import com.cinema.modules.session.entity.Session;
import com.cinema.modules.session.mapper.SessionMapper;
import com.cinema.modules.session.vo.SessionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionMapper sessionMapper;
    private final HallMapper hallMapper;
    private final CinemaMapper cinemaMapper;

    /** 影片某日在售场次(含影厅/影院信息) */
    public List<SessionVO> listByMovieAndDate(Long movieId, LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<Session> sessions = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getMovieId, movieId)
                .ge(Session::getStartTime, dayStart)
                .lt(Session::getStartTime, dayEnd)
                .orderByAsc(Session::getStartTime));
        if (sessions.isEmpty()) {
            return List.of();
        }

        Set<Long> hallIds = sessions.stream().map(Session::getHallId).collect(Collectors.toSet());
        Map<Long, Hall> halls = hallMapper.selectByIds(hallIds).stream()
                .collect(Collectors.toMap(Hall::getId, Function.identity()));
        Set<Long> cinemaIds = halls.values().stream().map(Hall::getCinemaId).collect(Collectors.toSet());
        Map<Long, Cinema> cinemas = cinemaIds.isEmpty() ? Map.of()
                : cinemaMapper.selectByIds(cinemaIds).stream()
                        .collect(Collectors.toMap(Cinema::getId, Function.identity()));

        return sessions.stream()
                .map(s -> SessionVO.from(s, halls.get(s.getHallId()),
                        halls.containsKey(s.getHallId())
                                ? cinemas.get(halls.get(s.getHallId()).getCinemaId()) : null))
                .toList();
    }

    public SessionVO detail(Long sessionId) {
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("场次不存在");
        }
        Hall hall = hallMapper.selectById(session.getHallId());
        Cinema cinema = hall == null ? null : cinemaMapper.selectById(hall.getCinemaId());
        return SessionVO.from(session, hall, cinema);
    }
}

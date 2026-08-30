package com.cinema.modules.session.vo;

import com.cinema.modules.cinema.entity.Cinema;
import com.cinema.modules.hall.entity.Hall;
import com.cinema.modules.session.entity.Session;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SessionVO {

    private Long id;
    private Long movieId;
    private Long hallId;
    private String hallName;
    private String cinemaName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal price;
    private Integer status;

    public static SessionVO from(Session session, Hall hall, Cinema cinema) {
        SessionVO vo = new SessionVO();
        vo.setId(session.getId());
        vo.setMovieId(session.getMovieId());
        vo.setHallId(session.getHallId());
        vo.setHallName(hall == null ? "" : hall.getName());
        vo.setCinemaName(cinema == null ? "" : cinema.getName());
        vo.setStartTime(session.getStartTime());
        vo.setEndTime(session.getEndTime());
        vo.setPrice(session.getPrice());
        vo.setStatus(session.getStatus());
        return vo;
    }
}

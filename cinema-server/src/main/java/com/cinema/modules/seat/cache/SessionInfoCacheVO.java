package com.cinema.modules.seat.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 场次元数据缓存值(session/movie/hall/cinema 聚合)
 * <p>读路径优化(Phase A-①): 原 seatMap 每次查 4 张 DB 表, 改为读这个 Redis Hash(1800s TTL).
 * 写路径失效由 AdminSessionController.delete(RedisKeys.sessionInfo(...)) 兜底.
 * <p>字段集合是 SeatMapVO 元数据的最小子集(price/startTime/title/hallName/seatRows/Cols/SeatCount)
 * + 必要的 status 用于入场校验. 额外带 cacheAt 用于排查陈旧度.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionInfoCacheVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;
    private Integer status;
    private LocalDateTime startTime;
    private BigDecimal price;

    private String movieTitle;
    private Long movieId;

    private String hallName;
    private Long hallId;
    private Integer seatRows;
    private Integer seatCols;
    private Integer seatCount;

    private String cinemaName;
    private Long cinemaId;

    /** 缓存写入时间(用于排查) */
    private LocalDateTime cacheAt;
}

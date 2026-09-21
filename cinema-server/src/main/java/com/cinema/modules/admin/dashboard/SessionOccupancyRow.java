package com.cinema.modules.admin.dashboard;

import lombok.Data;

/**
 * sessionOccupancyWeek SQL 投影行 — 场次 ID + 已售座位数.
 *
 * <p>Mapper 返回这个 typed DTO(替代原来的 Map&lt;String,Object&gt;),
 * TrendQuery 再用 sessionId 批量 join session/movie/hall.
 */
@Data
public class SessionOccupancyRow {
    /** 场次 ID */
    private Long sessionId;
    /** 该场次已售座位数(PAID 订单的 order_item 行数) */
    private Integer sold;
}
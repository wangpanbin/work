package com.cinema.modules.seat.vo;

import java.util.List;

/**
 * 影厅布局摘要(Redis 缓存 cinema:hall:layout:{hid})
 */
public record HallLayout(int rows, int cols, List<Integer> vipRowNos) {
}

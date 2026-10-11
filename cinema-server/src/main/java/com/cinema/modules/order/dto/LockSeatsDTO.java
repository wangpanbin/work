package com.cinema.modules.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class LockSeatsDTO {

    @NotNull(message = "场次不能为空")
    private Long sessionId;

    /**
     * 座位索引列表。
     *
     * <p>P0 修复(2026-10-11 稳定性测试): 原本只有 {@code @NotEmpty} + {@code @Size(max=4)},
     * 没有任何元素级约束, 于是 {@code null} 会让 {@code stream().sorted()} 抛 NPE(落 50000),
     * 负数会让 Redis 的 {@code GETBIT} 报 {@code ERR bit offset ...}(也落 50000),
     * 而<b>越界正数会被直接接受</b>并进到 {@code lock_seat.lua} 的 {@code SETBIT},
     * 让位图按 offset 无限膨胀(实测 offset=100000 → 位图放大 694 倍, 公开的 seat-map 响应放大 61 倍)。
     *
     * <p>这里补上元素级 {@code @NotNull} 与 {@code @Min(0)}; <b>上界不能写成常量注解</b> ——
     * 1 号厅 96 座、2 号厅 140 座, 真实上界必须按场次算, 由
     * {@code OrderCore#requireSeatsInRange} 在服务层用场次 seatCount 校验。
     * 这里的 {@code @Max} 只是一道远高于任何影厅的兜底, 防止明显离谱的值进入服务层。
     */
    @NotEmpty(message = "请至少选择一个座位")
    @Size(max = 4, message = "每次最多选择4个座位")
    private List<@NotNull(message = "座位索引不能为空")
                 @Min(value = 0, message = "座位索引不能为负数")
                 @Max(value = 4095, message = "座位索引超出影厅最大座位数") Integer> seatIndexes;
}

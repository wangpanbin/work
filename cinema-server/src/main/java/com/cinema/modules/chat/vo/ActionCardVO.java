package com.cinema.modules.chat.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * T1 cycle 3 — 行动卡片 VO(spec §6.1 schema).
 *
 * <p>卡片只携带展示与跳转所需的信息(sessionId + seatIndexes + 价格 + 描述),
 * <b>不携带任何可直接提交的载荷</b>(ADR-0002 — 不执行写操作).
 * 前端点击后跳到 {@code /seat/{sessionId}} 并通过 {@code preselect} 预选,
 * 锁座仍由用户在既有流程确认后经 {@code POST /orders/lock} 发生.
 *
 * cycle 3 阶段占位,T2 接入工具后由 ChatTools 填充.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionCardVO {
    /** SEAT_SUGGESTION 等(预留枚举空间,cycle 3 用 String 占位) */
    private String type;
    /** 雪花 ID 字符串,前端 URL 用 */
    private String sessionId;
    private String movieTitle;
    private String hallName;
    /** yyyy-MM-dd HH:mm:ss 格式 */
    private String startTime;
    private BigDecimal price;
    /** 座位索引列表(整数) */
    private List<Integer> seatIndexes;
    /** 行列表述,展示用,如 "5排4座、5排5座" */
    private String seatDesc;
    private BigDecimal totalAmount;
    /** 卡片按钮文案,如 "去选座确认" */
    private String actionLabel;
}
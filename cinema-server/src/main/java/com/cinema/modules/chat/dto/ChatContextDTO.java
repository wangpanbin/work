package com.cinema.modules.chat.dto;

import lombok.Data;

import java.util.List;

/**
 * T1 cycle 3 — 场次上下文 DTO(spec §6.1 context).
 *
 * <p>前端用 useChatContext.ts 组装,随每条消息带上,
 * 让模型能解析"这个还有座吗"等指代(避免从对话历史猜当前场次).
 *
 * <p>{@code sessionId} 是场次 ID(雪花 ID),<b>不是</b>聊天会话的
 * {@code chatSessionId}(后者在 {@link ChatRequestDTO} 上,不是 secting 字段).
 */
@Data
public class ChatContextDTO {
    /** 当前路由 path,如 "/movie/3" */
    private String route;
    /** 场次 ID(雪花 ID 字符串,前端 URL 走 String — 雪花 ID 超 2^53) */
    private String sessionId;
    /** 已选座位数(seatStore.selected.size) */
    private Integer seatCount;
    /** 已选座位索引列表 */
    private List<Integer> selectedSeatIndexes;
}
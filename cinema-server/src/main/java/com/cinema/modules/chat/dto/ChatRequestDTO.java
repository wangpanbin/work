package com.cinema.modules.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * T1 cycle 3 — 对话请求 DTO(完整 schema,spec §6.1).
 *
 * <ul>
 *   <li>{@code chatSessionId} — 前端首次进浮窗用 {@code crypto.randomUUID()} 生成,
 *             localStorage 持久化,同 id 路由同一 ChatMemory 桶(spec §4.4)</li>
 *   <li>{@code message} — 用户消息</li>
 *   <li>{@code context} — 场次上下文,让"这个还有座吗"等指代可解析(spec §6.1)</li>
 * </ul>
 *
 * 注意: {@code chatSessionId} 是聊天会话 id,<b>不是</b>场次 id.
 */
@Data
public class ChatRequestDTO {

    private String chatSessionId;

    @NotBlank(message = "消息内容不能为空")
    private String message;

    private ChatContextDTO context;
}
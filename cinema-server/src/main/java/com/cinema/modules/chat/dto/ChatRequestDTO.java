package com.cinema.modules.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * T1 cycle 2 — 对话请求 DTO(占位).
 *
 * <p>本 cycle 只用 {@code message} 字段验证 50000 短路路径。
 * {@code chatSessionId} + {@code context} 在 cycle 3 与 {@code ChatResponseVO}
 * 一起补(spec §6.1 schema 完整字段).
 */
@Data
public class ChatRequestDTO {

    @NotBlank(message = "消息内容不能为空")
    private String message;
}
package com.cinema.modules.chat.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * T1 cycle 3 — 对话响应 VO(spec §6.1 schema)。
 *
 * <p>cycle 3 阶段 {@code cards} / {@code followUps} 总是空列表(无 ChatTools 接入),
 * T2 接入工具后 ChatTools 解析模型输出填充 cards,T6 前端渲染卡片。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseVO {
    /**
     * 本次对话实际使用的会话 id(spec §6.1)。请求里没带 chatSessionId 时后端生成
     * fallback id 并通过本字段回传,客户端下次请求应复用它。
     */
    private String chatSessionId;
    private String reply;
    private List<ActionCardVO> cards;
    private List<String> followUps;
}
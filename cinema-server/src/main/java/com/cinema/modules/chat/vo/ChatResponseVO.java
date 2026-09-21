package com.cinema.modules.chat.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * T1 cycle 3 — 对话响应 VO(spec §6.1 schema).
 *
 * <p>cycle 3 阶段 {@code cards} / {@code followUps} 总是空列表(无 ChatTools 接入),
 * T2 接入工具后 ChatTools 解析模型输出填充 cards,T6 前端渲染卡片.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseVO {
    private String reply;
    private List<ActionCardVO> cards;
    private List<String> followUps;
}
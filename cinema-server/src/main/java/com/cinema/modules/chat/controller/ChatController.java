package com.cinema.modules.chat.controller;

import com.cinema.common.result.R;
import com.cinema.modules.chat.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 智能客服接口
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /** 发送消息, 获取 AI 回复 */
    @PostMapping
    public R<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        String reply = chatService.match(message);
        return R.ok(Map.of("reply", reply));
    }
}

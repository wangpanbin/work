package com.cinema.modules.chat.controller;

import com.cinema.common.annotation.IdempotencyKeys;
import com.cinema.common.annotation.RateLimit;
import com.cinema.common.result.R;
import com.cinema.modules.chat.dto.ChatRequestDTO;
import com.cinema.modules.chat.service.ChatAssistantService;
import com.cinema.modules.chat.vo.ChatResponseVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * T5 — 对话入口 Controller(spec §6.1 + §6.3)。
 *
 * <p>鉴权 + 限流:
 * <ul>
 *   <li>{@code /api/chat/**} 不在 {@code AuthRequiredInterceptor} 覆盖路径(spec §6.1)
 *       → 无 token 也可调,工具层 null userId 自动降级</li>
 *   <li>{@code @RateLimit} 限流:<b>匿名共用一个 2/min 桶</b>(anonymousPermits=2),
 *       <b>登录用户各自 10/min</b>(permits=10)</li>
 * </ul>
 *
 * <p>SpEL key 走 {@code IdempotencyKeys.RATE_CHAT_MESSAGE_BY_USER} 常量,
 * 那里用 {@code ?:} 短路 userId==null 时返回字面量 {@code 'anon'},
 * 避开 {@code T(...).userId()} 抛 NPE(1.x 对称行为,spec §5.4 (d) 提到);
 * 括号保证 {@code ?:} 不会被解析成 {@code userId() ?: ('anon' + ':chat')},
 * 登录用户的桶 key 因此带上了 {@code :chat} 后缀, 与匿名桶对称。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAssistantService chatAssistantService;

    /**
     * 对话消息。
     *
     * <p><b>{@code @Valid} 是必需的</b>(2026-10-24): {@link ChatRequestDTO#message} 上的
     * {@code @NotBlank} 只有在参数被校验时才生效。原先漏了 {@code @Valid},
     * 空消息会一路穿透到 LangChain4j → 抛错 → 返 {@code 50000 系统繁忙} ——
     * 客户端输入错误被上报成服务端故障, 污染监控与告警。
     * 加上之后空消息在入口就被拦下, 返 {@code 40001 参数错误}。
     */
    @PostMapping("/message")
    @RateLimit(
            key = IdempotencyKeys.RATE_CHAT_MESSAGE_BY_USER,
            permits = 10,
            anonymousPermits = 2,
            window = 1,
            unit = TimeUnit.MINUTES,
            message = "对话请求过于频繁,请稍后再试")
    public R<ChatResponseVO> chatMessage(@Valid @RequestBody ChatRequestDTO dto) {
        Optional<ChatResponseVO> voOpt = chatAssistantService.chat(
                dto.getChatSessionId(), dto.getMessage());
        if (voOpt.isEmpty()) {
            // ChatConfig 在 api-key 为空时不注册 CinemaAssistant Bean(cycle 1/3)
            return R.fail(50000, "对话功能未配置");
        }
        return R.ok(voOpt.get());
    }
}
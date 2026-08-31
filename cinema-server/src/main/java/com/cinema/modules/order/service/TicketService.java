package com.cinema.modules.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cinema.common.crypto.HmacSigner;
import com.cinema.common.exception.BizException;
import com.cinema.common.result.ResultCode;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.Ticket;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.mapper.TicketMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 电子票服务 — N2 支付成功后生成 + 扫码验证.
 * <p>支付成功 → generate(orderNo) 写 ticket 表 + Redis 缓存 base64 payload
 * <p>验票端点 → verify(payload, sig) 校验签名 + 一次性(已验则失败)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final long TICKET_TTL_HOURS = 24;

    private final TicketMapper ticketMapper;
    private final OrderMapper orderMapper;
    private final OrderCore orderCore;
    private final HmacSigner signer;
    private final ObjectMapper objectMapper;

    /**
     * 支付成功后调用: 生成票根(幂等, 已有则直接返回)
     */
    public Ticket generate(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BizException("订单不存在");
        }
        // 已有票则返回(幂等)
        Ticket existing = ticketMapper.selectById(orderNo);
        if (existing != null) {
            return existing;
        }

        LocalDateTime exp = LocalDateTime.now().plusHours(TICKET_TTL_HOURS);
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> payloadMap = Map.of(
                "orderNo", orderNo,
                "userId", order.getUserId(),
                "sessionId", order.getSessionId(),
                "exp", exp.toString(),
                "nonce", nonce);
        try {
            String payloadJson = objectMapper.writeValueAsString(payloadMap);
            String sig = signer.sign(payloadJson);
            String base64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes());

            Ticket t = new Ticket();
            t.setOrderNo(orderNo);
            t.setUserId(order.getUserId());
            t.setSessionId(order.getSessionId());
            t.setPayload(base64);
            t.setSig(sig);
            t.setExpAt(exp);
            t.setCreatedAt(LocalDateTime.now());
            ticketMapper.insert(t);
            log.info("[电子票] orderNo={} 生成成功, exp={}", orderNo, exp);
            return t;
        } catch (Exception e) {
            throw new IllegalStateException("电子票生成失败: " + orderNo, e);
        }
    }

    /**
     * 用户取票: 校验订单属于本人, 返回 base64 + sig
     */
    public Map<String, String> getTicket(String orderNo, Long userId) {
        Order order = orderCore.getOwnedOrder(orderNo, userId);
        if (order.getStatus() == null || order.getStatus() != 1) {
            // 仅 PAID 状态可取票
            throw new BizException("订单未支付,无法取票");
        }
        Ticket t = ticketMapper.selectById(orderNo);
        if (t == null) {
            // 兜底: 历史订单未生成, 这里补一次
            t = generate(orderNo);
        }
        if (t.getExpAt().isBefore(LocalDateTime.now())) {
            throw new BizException("电子票已过期,请联系影院");
        }
        return Map.of(
                "payload", t.getPayload(),
                "sig", t.getSig(),
                "expAt", t.getExpAt().toString(),
                "orderNo", orderNo);
    }

    /**
     * 验票(公开端点): 校验签名 + 一次性(已验证则返"已使用")
     * @return true 验证通过; false 验证失败
     */
    public VerifyResult verify(String payload, String sig) {
        if (payload == null || sig == null) {
            return VerifyResult.failed("参数不完整");
        }
        if (!signer.verify(decode(payload), sig)) {
            return VerifyResult.failed("签名校验失败");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(decode(payload), new TypeReference<Map<String, Object>>() {});
            String orderNo = (String) map.get("orderNo");
            if (orderNo == null) {
                return VerifyResult.failed("payload 缺少 orderNo");
            }
            Ticket t = ticketMapper.selectById(orderNo);
            if (t == null) {
                return VerifyResult.failed("票根不存在");
            }
            if (t.getExpAt().isBefore(LocalDateTime.now())) {
                return VerifyResult.failed("电子票已过期");
            }
            if (t.getVerifiedAt() != null) {
                return VerifyResult.used(t.getVerifiedAt().toString());
            }
            // 标记已使用
            t.setVerifiedAt(LocalDateTime.now());
            ticketMapper.updateById(t);
            return VerifyResult.ok(orderNo, t.getUserId(), t.getSessionId());
        } catch (Exception e) {
            log.warn("[验票] payload 解析失败 err={}", e.toString());
            return VerifyResult.failed("payload 解析失败");
        }
    }

    private String decode(String base64) {
        try {
            return new String(Base64.getUrlDecoder().decode(base64));
        } catch (Exception e) {
            return "";
        }
    }

    public record VerifyResult(boolean valid, String orderNo, Long userId, Long sessionId,
                               String verifiedAt, String message) {
        public static VerifyResult ok(String orderNo, Long userId, Long sessionId) {
            return new VerifyResult(true, orderNo, userId, sessionId, null, "OK");
        }
        public static VerifyResult used(String verifiedAt) {
            return new VerifyResult(false, null, null, null, verifiedAt, "已使用");
        }
        public static VerifyResult failed(String msg) {
            return new VerifyResult(false, null, null, null, null, msg);
        }
    }
}

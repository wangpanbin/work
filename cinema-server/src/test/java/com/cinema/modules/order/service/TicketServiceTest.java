package com.cinema.modules.order.service;

import com.cinema.common.crypto.HmacSigner;
import com.cinema.common.exception.BizException;
import com.cinema.modules.order.entity.Order;
import com.cinema.modules.order.entity.Ticket;
import com.cinema.modules.order.enums.OrderStatus;
import com.cinema.modules.order.mapper.OrderMapper;
import com.cinema.modules.order.mapper.TicketMapper;
import com.cinema.modules.order.service.core.OrderCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TicketService} 单元测试 —— N2 电子票域此前**零覆盖**。
 *
 * <p>这个类同时负责两件事: 签发(HMAC 签名 + Base64URL)与验票(签名校验 + 一次性)。
 * 「一次性」是它最关键也最脆的语义, 而它此前没有任何测试兜着 ——
 * 于是「读-改-写」的非原子实现(两个检票员并发扫同一张票可都拿到 valid=true → 一票两用)
 * 一直没被发现。2026-10-24 改成 {@code WHERE verified_at IS NULL} 的条件更新, 本类锁住该契约。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketServiceTest {

    private static final String ORDER_NO = "ORD-TICKET-1";
    private static final Long USER_ID = 100L;
    private static final Long SESSION_ID = 7L;

    @Mock private TicketMapper ticketMapper;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderCore orderCore;

    private HmacSigner signer;
    private ObjectMapper objectMapper;
    private TicketService service;
    /** 整条用例共用的 payload 原文(见 {@link #rawPayloadJson()} 的说明)。 */
    private String cachedPayloadJson;

    @BeforeEach
    void setUp() {
        cachedPayloadJson = null;
        signer = new HmacSigner();
        ReflectionTestUtils.setField(signer, "secret", "unit-test-secret");
        objectMapper = new ObjectMapper();
        service = new TicketService(ticketMapper, orderMapper, orderCore, signer, objectMapper);
    }

    // ------------------------------------------------------------------ 签发

    @Test
    @DisplayName("签发: 支付后生成票根, payload/sig/expAt 齐全")
    void generate_createsTicket() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(paidOrder());
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(null);

        Ticket t = service.generate(ORDER_NO);

        assertThat(t.getOrderNo()).isEqualTo(ORDER_NO);
        assertThat(t.getUserId()).isEqualTo(USER_ID);
        assertThat(t.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(t.getPayload()).isNotBlank();
        assertThat(t.getSig()).isNotBlank();
        assertThat(t.getExpAt()).isAfter(LocalDateTime.now());
        verify(ticketMapper).insert(any(Ticket.class));
    }

    @Test
    @DisplayName("签发幂等: 已有票直接返回, 不重复插入")
    void generate_isIdempotent() {
        Ticket existing = new Ticket();
        existing.setOrderNo(ORDER_NO);
        existing.setPayload("payload");
        existing.setSig("sig");
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(paidOrder());
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(existing);

        Ticket t = service.generate(ORDER_NO);

        assertThat(t).isSameAs(existing);
        verify(ticketMapper, never()).insert(any(Ticket.class));
    }

    @Test
    @DisplayName("签发拒绝: 订单不存在抛 BizException")
    void generate_orderNotFound() {
        when(orderMapper.selectByOrderNo(ORDER_NO)).thenReturn(null);

        assertThatThrownBy(() -> service.generate(ORDER_NO))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单不存在");
    }

    // ------------------------------------------------------------------ 取票

    @Test
    @DisplayName("取票: 仅 PAID 订单可取, 返回 payload 与签名")
    void getTicket_paidOrder() {
        Ticket t = validTicket(LocalDateTime.now().plusHours(1));
        when(orderCore.getOwnedOrder(ORDER_NO, USER_ID)).thenReturn(paidOrder());
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(t);

        Map<String, String> res = service.getTicket(ORDER_NO, USER_ID);

        assertThat(res.get("payload")).isEqualTo("payload");
        assertThat(res.get("sig")).isEqualTo("sig");
        assertThat(res.get("orderNo")).isEqualTo(ORDER_NO);
    }

    @Test
    @DisplayName("取票拒绝: 订单未支付抛 BizException")
    void getTicket_notPaid() {
        Order pending = paidOrder();
        pending.setStatus(OrderStatus.PENDING_PAY.getCode());
        when(orderCore.getOwnedOrder(ORDER_NO, USER_ID)).thenReturn(pending);

        assertThatThrownBy(() -> service.getTicket(ORDER_NO, USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单未支付");
    }

    @Test
    @DisplayName("取票拒绝: 电子票已过期抛 BizException")
    void getTicket_expired() {
        when(orderCore.getOwnedOrder(ORDER_NO, USER_ID)).thenReturn(paidOrder());
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(validTicket(LocalDateTime.now().minusMinutes(1)));

        assertThatThrownBy(() -> service.getTicket(ORDER_NO, USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("电子票已过期");
    }

    // ------------------------------------------------------------------ 验票

    @Test
    @DisplayName("验票通过: 首次扫码标记已使用并返回票面信息")
    void verify_firstScan_passes() {
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(validTicket(LocalDateTime.now().plusHours(1)));
        when(ticketMapper.markVerifiedIfUnused(eq(ORDER_NO), any(LocalDateTime.class))).thenReturn(1);

        TicketService.VerifyResult res = service.verify(payload(), sig());

        assertThat(res.valid()).isTrue();
        assertThat(res.orderNo()).isEqualTo(ORDER_NO);
        assertThat(res.userId()).isEqualTo(USER_ID);
        assertThat(res.sessionId()).isEqualTo(SESSION_ID);
        verify(ticketMapper).markVerifiedIfUnused(eq(ORDER_NO), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("一次性契约: 原子标记影响 0 行 = 已被别人验过, 返「已使用」而不是「通过」")
    void verify_concurrentScan_secondOneIsRejected() {
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(validTicket(LocalDateTime.now().plusHours(1)));
        // 并发下第二个检票员: 条件更新影响 0 行
        when(ticketMapper.markVerifiedIfUnused(eq(ORDER_NO), any(LocalDateTime.class))).thenReturn(0);
        Ticket used = validTicket(LocalDateTime.now().plusHours(1));
        used.setVerifiedAt(LocalDateTime.now().minusSeconds(3));
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(validTicket(LocalDateTime.now().plusHours(1)), used);

        TicketService.VerifyResult res = service.verify(payload(), sig());

        assertThat(res.valid()).isFalse();
        assertThat(res.message()).isEqualTo("已使用");
        assertThat(res.verifiedAt()).isNotBlank();
    }

    @Test
    @DisplayName("验票: 已验过的票直接返「已使用」, 不再发 UPDATE")
    void verify_alreadyUsed() {
        Ticket used = validTicket(LocalDateTime.now().plusHours(1));
        used.setVerifiedAt(LocalDateTime.now().minusMinutes(1));
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(used);

        TicketService.VerifyResult res = service.verify(payload(), sig());

        assertThat(res.valid()).isFalse();
        assertThat(res.message()).isEqualTo("已使用");
        verify(ticketMapper, never()).markVerifiedIfUnused(anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("验票拒绝: 签名不匹配")
    void verify_badSignature() {
        TicketService.VerifyResult res = service.verify(payload(), "forged-signature");

        assertThat(res.valid()).isFalse();
        assertThat(res.message()).isEqualTo("签名校验失败");
        verify(ticketMapper, never()).markVerifiedIfUnused(anyString(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("验票拒绝: 参数不完整")
    void verify_missingArgs() {
        assertThat(service.verify(null, "sig").message()).isEqualTo("参数不完整");
        assertThat(service.verify("payload", null).message()).isEqualTo("参数不完整");
    }

    @Test
    @DisplayName("验票拒绝: 票根不存在")
    void verify_ticketNotFound() {
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(null);

        TicketService.VerifyResult res = service.verify(payload(), sig());

        assertThat(res.valid()).isFalse();
        assertThat(res.message()).isEqualTo("票根不存在");
    }

    @Test
    @DisplayName("验票拒绝: 电子票已过期")
    void verify_expired() {
        when(ticketMapper.selectById(ORDER_NO)).thenReturn(validTicket(LocalDateTime.now().minusMinutes(1)));

        TicketService.VerifyResult res = service.verify(payload(), sig());

        assertThat(res.valid()).isFalse();
        assertThat(res.message()).isEqualTo("电子票已过期");
    }

    // ------------------------------------------------------------------ helpers

    private Order paidOrder() {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(ORDER_NO);
        o.setUserId(USER_ID);
        o.setSessionId(SESSION_ID);
        o.setStatus(OrderStatus.PAID.getCode());
        return o;
    }

    private Ticket validTicket(LocalDateTime expAt) {
        Ticket t = new Ticket();
        t.setOrderNo(ORDER_NO);
        t.setUserId(USER_ID);
        t.setSessionId(SESSION_ID);
        t.setPayload("payload");
        t.setSig("sig");
        t.setExpAt(expAt);
        return t;
    }

    /** 与 {@code TicketService#generate} 同构的 payload(JSON → Base64URL, 无 padding)。 */
    private String payload() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rawPayloadJson().getBytes(StandardCharsets.UTF_8));
    }

    /** 与 payload 一一对应的 HMAC 签名。 */
    private String sig() {
        return signer.sign(rawPayloadJson());
    }

    /**
     * payload 的原始 JSON —— <b>整条用例共用同一份</b>。
     *
     * <p>签名是对「这段 JSON 原文」算的, 所以 payload 与签名必须来自同一段文本。
     * 若每次调用都重新生成, {@code LocalDateTime.now()} 的微秒差异会让两者对不上,
     * 用例会假失败在「签名校验失败」上。
     */
    private String rawPayloadJson() {
        if (cachedPayloadJson == null) {
            cachedPayloadJson = "{\"orderNo\":\"" + ORDER_NO + "\",\"userId\":" + USER_ID
                    + ",\"sessionId\":" + SESSION_ID
                    + ",\"exp\":\"" + LocalDateTime.now().plusHours(24) + "\",\"nonce\":\"abc123\"}";
        }
        return cachedPayloadJson;
    }
}

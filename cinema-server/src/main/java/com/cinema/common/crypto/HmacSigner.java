package com.cinema.common.crypto;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HMAC-SHA256 签名工具 — N2 二维码签名
 * <p>密钥从 application.yml 读, 默认 dev/test 用
 */
@Component
@RequiredArgsConstructor
public class HmacSigner {

    @Value("${cinema.ticket.secret:dev-only-secret-do-not-use-in-prod}")
    private String secret;

    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    public boolean verify(String payload, String sig) {
        return sign(payload).equals(sig);
    }
}

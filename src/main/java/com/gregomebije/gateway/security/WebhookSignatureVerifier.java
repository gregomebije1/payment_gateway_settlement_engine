package com.gregomebije.gateway.security;

import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class WebhookSignatureVerifier {

    public boolean verify(String timestamp, String signature, String rawBody, String secret) {
        if (timestamp == null || signature == null || rawBody == null || secret == null) {
            return false;
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }

        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - ts) > 300) return false;

        String signedPayload = timestamp + "." + rawBody;
        String expected = HmacUtils
                .hmacSha256Hex(secret.getBytes(StandardCharsets.UTF_8),
                               signedPayload.getBytes(StandardCharsets.UTF_8));

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}

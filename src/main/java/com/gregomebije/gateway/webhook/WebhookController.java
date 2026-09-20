package com.gregomebije.gateway.webhook;

import com.gregomebije.gateway.payment.PaymentService;
import com.gregomebije.gateway.security.WebhookSignatureVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settlements/webhooks")
public class WebhookController {
    private final WebhookSignatureVerifier verifier;
    private final PaymentService payments;
    private final String secret;

    public WebhookController(WebhookSignatureVerifier verifier,
                             PaymentService payments,
                             @Value("${gateway.webhook-secret}") String secret) {
        this.verifier = verifier;
        this.payments = payments;
        this.secret = secret;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Void> stripe(
            @RequestHeader("X-Signature-Timestamp") String timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String rawBody) {

        if (!verifier.verify(timestamp, signature, rawBody, secret)) {
            return ResponseEntity.status(401).build();
        }

        String reference = extract(rawBody, "reference");
        if (reference != null) payments.markSucceeded(reference);
        return ResponseEntity.ok().build();
    }

    private String extract(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }
}

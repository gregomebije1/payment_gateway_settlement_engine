
package com.gregomebije.gateway.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}
 
    public record CreateUserRequest(String tenant) {}
    public record CreateChargeRequest(UUID tenantId,
                UUID idempotencyKey, 
                UUID entryId, 
                UUID msg1) {}

    public record GatewayWebhookPayload(
        String type,
        UUID charge_id
    ) {}

    public record CaptureResult(
        UUID paymentId,
        String status
    ) {}
}
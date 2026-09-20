package com.gregomebije.gateway.payment;

import java.math.BigDecimal;

public record PaymentResponse(
        String reference, BigDecimal amount, String currency, PaymentStatus status) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getReference(), p.getAmount(), p.getCurrency(), p.getStatus());
    }
}

package com.gregomebije.gateway.payment;

import com.gregomebije.gateway.idempotency.IdempotencyService;
import com.gregomebije.gateway.outbox.OutboxEvent;
import com.gregomebije.gateway.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final IdempotencyService idempotency;

    public PaymentService(PaymentRepository payments, OutboxRepository outbox,
                          IdempotencyService idempotency) {
        this.payments = payments;
        this.outbox = outbox;
        this.idempotency = idempotency;
    }

    @Transactional
    public PaymentResponse create(String idempotencyKey, PaymentRequest request) {
        if (!idempotency.acquire(idempotencyKey)) {
            return payments.findByReference(request.reference())
                    .map(PaymentResponse::from)
                    .orElseThrow(() -> new IllegalStateException("Request already processing"));
        }

        Payment payment = payments.save(
                new Payment(request.reference(), request.amount(), request.currency()));

        outbox.save(new OutboxEvent(
                "Payment", payment.getReference(), "PaymentCreated",
                "{\"reference\":\"" + payment.getReference() + "\"}"));

        return PaymentResponse.from(payment);
    }

    @Transactional
    public void createAndFail(String idempotencyKey, PaymentRequest request) {
        if (!idempotency.acquire(idempotencyKey)) {
            throw new IllegalStateException("duplicate");
        }
        Payment payment = payments.save(
                new Payment(request.reference(), request.amount(), request.currency()));
        outbox.save(new OutboxEvent(
                "Payment", payment.getReference(), "PaymentCreated", "{}"));
        throw new IllegalStateException("forced rollback");
    }

    @Transactional
    public void markSucceeded(String reference) {
        Payment payment = payments.findByReference(reference)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
        payment.markSucceeded();
    }
}

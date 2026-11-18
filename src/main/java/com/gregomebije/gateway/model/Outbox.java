package com.gregomebije.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String target;

    @Column(nullable = false)
    private String path;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Object body;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder.Default
    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt = OffsetDateTime.now();

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    // Custom constructor ensuring nextAttemptAt is initialized
    public Outbox(UUID tenantId, String target, String path, String idempotencyKey, Object body) {
        this.tenantId = tenantId;
        this.target = target;
        this.path = path;
        this.idempotencyKey = idempotencyKey;
        this.body = body;
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = OffsetDateTime.now();
    }
    
    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.publishedAt = OffsetDateTime.now();
    }

    public void markFailed(int maxAttempts) {
        this.attempts++;
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.DEAD_LETTER;
        } else {
            long backoffSeconds = (long) Math.pow(2, this.attempts);
            this.nextAttemptAt = OffsetDateTime.now().plusSeconds(backoffSeconds);
        }
    }

    public enum OutboxStatus {
        PENDING, SENT, DEAD_LETTER
    }
}
package com.gregomebije.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payouts")

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", columnDefinition = "TEXT", unique = true, nullable = false)
    private String idempotencyKey;

    @Column(name = "tenant", columnDefinition = "TEXT", nullable = false)
    private String tenant;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "status", columnDefinition = "TEXT", nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

}

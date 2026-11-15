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
@Table(name = "payments")

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider_ref", columnDefinition = "TEXT", unique = true, nullable = false)
    private String providerRef;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;


    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long fee;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

}

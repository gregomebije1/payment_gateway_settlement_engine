package com.gregomebije.gateway.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "tenant",
    check = @CheckConstraint(
        name = "ck_tenant_available_non_negative",
        constraint = "available >= 0"
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Min(0)
    @Column(nullable = false)
    @Builder.Default
    private long available = 0L;

}

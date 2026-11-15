package com.gregomebije.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import jakarta.validation.constraints.Min;
import org.hibernate.annotations.Check;
import java.util.UUID;

@Entity
@Table(name = "tenant")
@Check(name = "ck_entry_one_side", constraints = "(available >= 0)")

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

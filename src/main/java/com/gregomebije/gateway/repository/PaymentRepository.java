package com.gregomebije.gateway.repository;

import org.springframework.data.jpa.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

import com.gregomebije.model.Payment;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdAndTenantId(UUID id, UUID tenantId);
}


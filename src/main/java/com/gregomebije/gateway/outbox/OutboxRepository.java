package com.gregomebije.gateway.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    long countByPublishedFalse();
}

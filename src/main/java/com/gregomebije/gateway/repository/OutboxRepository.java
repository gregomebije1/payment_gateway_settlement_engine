package com.gregomebije.gateway.repository;

import com.gregomebije.gateway.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {
    /**
     * WHERE status = 'PENDING' AND next_attempt_at <= NOW() picks only 
     * unsent messages whose retry timer has expired.
     • ORDER BY id ASC LIMIT :batchSize grabs the oldest 50 messages first 
     * (preserving order).
     • FOR UPDATE SKIP LOCKED is the magic for horizontal scaling: it locks 
     * those 50 rows for the current database transaction. If you run 3 copies
     * of your Spring Boot app at the same time, Instance #2 will skip 
     * the 50 rows locked by Instance #1 and immediately 
     * grab rows 51–100 instead of waiting or sending duplicates.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= NOW()
            ORDER BY id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Outbox> claimBatchForProcessing(@Param("batchSize") int batchSize);
}
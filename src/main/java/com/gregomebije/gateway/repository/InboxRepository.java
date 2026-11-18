package com.gregomebije.gateway.repository;

import com.gregomebije.gateway.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboxRepository extends JpaRepository<Inbox, InboxId> {
    @Modifying
    //@Modifying tells Spring Data JPA that this query changes rows 
    // (INSERT/UPDATE/DELETE) rather than fetching data, and returns 
    // an int representing how many rows were affected
    //If (tenantId, messageId) is new, PostgreSQL inserts it and returns 1. 
    // If it already exists, PostgreSQL silently ignores it and returns 0

    @Query(value = """
            INSERT INTO inbox (tenant_id, message_id)
            VALUES (:tenantId, :messageId)
            ON CONFLICT (tenant_id, message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreConflict(@Param("tenantId") UUID tenantId, @Param("messageId") String messageId);
}
package com.gregomebije.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;


@Entity
@Table(name = "inbox")
public class InboxMessage {
    @EmbeddedId
    private InboxId id;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    protected InboxMessage() {}
    public InboxMessage(UUID tenantId, UUID messageId) {
        this.id = new InboxId(tenantId, messageId);
    }
}

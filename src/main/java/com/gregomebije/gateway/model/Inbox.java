package com.gregomebije.gateway.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "inbox")
public class Inbox {
    @EmbeddedId
    private InboxId id;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    protected Inbox() {}
    public Inbox(UUID tenantId, String messageId) {
        this.id = new InboxId(tenantId, messageId);
    }
}

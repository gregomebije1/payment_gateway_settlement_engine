package com.gregomebije.gateway.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "inbox")
public class Inbox {
    @EmbeddedId
    private InboxId id;

    @Generated(event = EventType.INSERT)
    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    protected Inbox() {}
    public Inbox(UUID tenantId, String messageId) {
        this.id = new InboxId(tenantId, messageId);
    }
}

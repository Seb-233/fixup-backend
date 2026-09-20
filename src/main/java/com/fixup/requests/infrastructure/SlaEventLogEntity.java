package com.fixup.requests.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sla_events_log")
class SlaEventLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "event_type", nullable = false, updatable = false, length = 16)
    private String eventType;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SlaEventLogEntity() {
    }

    static SlaEventLogEntity warningFor(UUID requestId, Instant now) {
        var e = new SlaEventLogEntity();
        e.requestId = requestId;
        e.eventType = "SLA_WARNING";
        e.createdAt = now;
        return e;
    }

    static SlaEventLogEntity breachFor(UUID requestId, Instant now) {
        var e = new SlaEventLogEntity();
        e.requestId = requestId;
        e.eventType = "SLA_BREACH";
        e.createdAt = now;
        return e;
    }
}

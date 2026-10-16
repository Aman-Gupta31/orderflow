package com.aman.orderflow.messaging.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "event_audit_log")
public class EventAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt;

    protected EventAuditLog() {
        // JPA
    }

    public EventAuditLog(String eventId, String eventType, String payloadJson) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }

    @PrePersist
    void onCreate() {
        this.consumedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}

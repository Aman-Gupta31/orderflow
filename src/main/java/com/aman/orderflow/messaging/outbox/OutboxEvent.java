package com.aman.orderflow.messaging.outbox;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    @Column(nullable = false)
    private boolean published = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String eventId, String aggregateType, Long aggregateId, String eventType, String payloadJson) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.published = true;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

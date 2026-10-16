package com.aman.orderflow.messaging.event;

import java.time.Instant;

/**
 * Versioned event envelope: every event this app publishes carries its own
 * type name and schema version, independent of the Java class that happens to
 * represent it today. A consumer written against "OrderCreated v1" can keep
 * working even if a future "OrderCreated v2" adds fields - version is explicit
 * data on the wire, not inferred from a Java class name that could be renamed
 * or refactored without anyone intending a wire-format change.
 */
public record DomainEvent<T>(
        String eventId,
        String eventType,
        int version,
        Instant occurredAt,
        T payload
) {
    public static <T> DomainEvent<T> of(String eventId, String eventType, int version, T payload) {
        return new DomainEvent<>(eventId, eventType, version, Instant.now(), payload);
    }
}

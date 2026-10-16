package com.aman.orderflow.messaging.outbox;

import com.aman.orderflow.messaging.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Called from inside an existing @Transactional business method (OrderService,
 * PaymentService) to enqueue an event in the SAME transaction as the business
 * change it describes - never calls Kafka directly. See OutboxRelay for the
 * half of this pattern that actually talks to Kafka.
 */
@Component
public class OutboxEventWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void enqueue(String aggregateType, Long aggregateId, String eventType, int version, Object payload) {
        String eventId = UUID.randomUUID().toString();
        DomainEvent<Object> event = DomainEvent.of(eventId, eventType, version, payload);
        repository.save(new OutboxEvent(eventId, aggregateType, aggregateId, eventType, toJson(event)));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
    }
}

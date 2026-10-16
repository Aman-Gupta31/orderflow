package com.aman.orderflow.messaging.audit;

import com.aman.orderflow.messaging.KafkaTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "useful asynchronous task" consumer (Module G): maintains a durable,
 * queryable audit trail of every order/payment event actually processed.
 * Idempotent by construction - event_audit_log.event_id is UNIQUE, so a
 * redelivered event (at-least-once producer semantics from OutboxRelay,
 * consumer restart, rebalance) either gets caught by the existsByEventId
 * check or, in a genuine race, by the database's own constraint - both paths
 * are treated as "already handled," not an error. Malformed messages are
 * rethrown so Kafka's configured error handler (retry then dead-letter - see
 * KafkaErrorHandlingConfig) can take over instead of this method silently
 * swallowing a message it couldn't process.
 */
@Component
public class OrderEventAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventAuditConsumer.class);

    private final EventAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public OrderEventAuditConsumer(EventAuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = {KafkaTopics.ORDER_EVENTS, KafkaTopics.PAYMENT_EVENTS}, groupId = "orderflow-audit")
    public void onEvent(String rawEventJson) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(rawEventJson);
        } catch (Exception e) {
            log.error("Could not parse event envelope, handing off to the error handler: {}", rawEventJson, e);
            throw new IllegalArgumentException("Malformed event envelope", e);
        }

        String eventId = envelope.path("eventId").asText(null);
        String eventType = envelope.path("eventType").asText("UNKNOWN");
        if (eventId == null) {
            throw new IllegalArgumentException("Event envelope missing eventId: " + rawEventJson);
        }

        recordIfNotAlreadyProcessed(eventId, eventType, rawEventJson);
    }

    @Transactional
    void recordIfNotAlreadyProcessed(String eventId, String eventType, String rawEventJson) {
        if (repository.existsByEventId(eventId)) {
            log.info("Event {} ({}) already in the audit log - skipping (idempotent consumption)", eventId, eventType);
            return;
        }
        try {
            repository.save(new EventAuditLog(eventId, eventType, rawEventJson));
            log.info("Recorded event {} ({}) in the audit log", eventId, eventType);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another delivery of the same event - also fine.
            log.info("Event {} ({}) was recorded concurrently - skipping", eventId, eventType);
        }
    }
}

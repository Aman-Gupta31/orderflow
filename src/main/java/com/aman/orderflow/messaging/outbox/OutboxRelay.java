package com.aman.orderflow.messaging.outbox;

import com.aman.orderflow.messaging.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The half of the transactional outbox pattern that actually talks to Kafka.
 * Polls for events OutboxEventWriter enqueued (in the same transaction as the
 * business change they describe), publishes each one, and marks it published
 * only after a successful send - so a crash between "send succeeded" and
 * "marked published" just means the next poll sends it again (at-least-once,
 * not exactly-once), which is why the consumer side (OrderEventAuditConsumer)
 * is built to be idempotent rather than assuming single delivery.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public OutboxRelay(OutboxEventRepository repository, KafkaTemplate<Object, Object> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findByPublishedFalseOrderByIdAsc(Limit.of(BATCH_SIZE));
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            String topic = topicFor(event.getAggregateType());
            kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayloadJson())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
        } catch (Exception e) {
            // Left unpublished on purpose - the next poll retries. Do not let
            // one bad event block the rest of the batch.
            log.error("Failed to publish outbox event {} ({}) - will retry on next poll",
                    event.getEventId(), event.getEventType(), e);
        }
    }

    private String topicFor(String aggregateType) {
        return switch (aggregateType) {
            case "ORDER" -> KafkaTopics.ORDER_EVENTS;
            case "PAYMENT" -> KafkaTopics.PAYMENT_EVENTS;
            default -> throw new IllegalStateException("Unknown outbox aggregate type: " + aggregateType);
        };
    }
}

package com.aman.orderflow.messaging;

import com.aman.orderflow.messaging.audit.EventAuditLog;
import com.aman.orderflow.messaging.audit.EventAuditLogRepository;
import com.aman.orderflow.messaging.audit.OrderEventAuditConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-tests the idempotent-consumption logic directly (no real or embedded
 * Kafka broker involved) - the actual @KafkaListener wiring against a real
 * broker is verified live instead, see docs/TESTING.md.
 */
class OrderEventAuditConsumerTest {

    @Mock
    private EventAuditLogRepository repository;

    private OrderEventAuditConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new OrderEventAuditConsumer(repository, new ObjectMapper());
    }

    private String envelope(String eventId, String eventType) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType + "\",\"payload\":{}}";
    }

    @Test
    void onEvent_recordsANewEvent() {
        when(repository.existsByEventId("evt-1")).thenReturn(false);

        consumer.onEvent(envelope("evt-1", "OrderCreated"));

        verify(repository).save(any(EventAuditLog.class));
    }

    @Test
    void onEvent_skipsARedeliveredEvent_alreadyInTheAuditLog() {
        when(repository.existsByEventId("evt-2")).thenReturn(true);

        consumer.onEvent(envelope("evt-2", "OrderCreated"));

        verify(repository, never()).save(any());
    }

    @Test
    void onEvent_treatsAConcurrentDuplicateSaveAsAlreadyProcessed_notAnError() {
        when(repository.existsByEventId("evt-3")).thenReturn(false);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        // Should not throw - a lost race with another delivery of the same
        // event is an expected, handled outcome, not a failure.
        consumer.onEvent(envelope("evt-3", "OrderCreated"));
    }

    @Test
    void onEvent_rejectsAMalformedEnvelope_soTheErrorHandlerCanRetryOrDeadLetterIt() {
        assertThatThrownBy(() -> consumer.onEvent("not valid json"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }
}

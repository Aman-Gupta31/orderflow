package com.aman.orderflow.messaging;

import com.aman.orderflow.messaging.outbox.OutboxEvent;
import com.aman.orderflow.messaging.outbox.OutboxEventRepository;
import com.aman.orderflow.messaging.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<Object, Object> kafkaTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        relay = new OutboxRelay(repository, kafkaTemplate);
    }

    @Test
    void publishPending_marksEventPublished_whenSendSucceeds() {
        OutboxEvent event = new OutboxEvent("evt-1", "ORDER", 5L, "OrderCreated", "{}");
        when(repository.findByPublishedFalseOrderByIdAsc(any())).thenReturn(List.of(event));
        CompletableFuture<SendResult<Object, Object>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(eq(KafkaTopics.ORDER_EVENTS), eq("5"), eq("{}"))).thenReturn(future);

        relay.publishPending();

        assertThat(event.isPublished()).isTrue();
    }

    @Test
    void publishPending_leavesEventUnpublished_whenSendFails_soNextPollRetries() {
        OutboxEvent event = new OutboxEvent("evt-2", "ORDER", 6L, "OrderCreated", "{}");
        when(repository.findByPublishedFalseOrderByIdAsc(any())).thenReturn(List.of(event));
        CompletableFuture<SendResult<Object, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(eq(KafkaTopics.ORDER_EVENTS), eq("6"), eq("{}"))).thenReturn(failed);

        relay.publishPending();

        assertThat(event.isPublished()).isFalse();
    }

    @Test
    void publishPending_continuesPastAFailedEvent_toPublishLaterOnesInTheSameBatch() {
        OutboxEvent failing = new OutboxEvent("evt-3", "ORDER", 7L, "OrderCreated", "{}");
        OutboxEvent succeeding = new OutboxEvent("evt-4", "PAYMENT", 8L, "PaymentSucceeded", "{}");
        when(repository.findByPublishedFalseOrderByIdAsc(any())).thenReturn(List.of(failing, succeeding));

        CompletableFuture<SendResult<Object, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(eq(KafkaTopics.ORDER_EVENTS), eq("7"), eq("{}"))).thenReturn(failedFuture);
        when(kafkaTemplate.send(eq(KafkaTopics.PAYMENT_EVENTS), eq("8"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        assertThat(failing.isPublished()).isFalse();
        assertThat(succeeding.isPublished()).isTrue();
    }
}

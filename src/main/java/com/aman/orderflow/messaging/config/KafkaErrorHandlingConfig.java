package com.aman.orderflow.messaging.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Applied automatically by Spring Boot's Kafka auto-configuration to every
 * @KafkaListener container, because it's the single CommonErrorHandler bean in
 * the context. A message that throws is retried 3 times, 500ms apart; if it
 * still fails, DeadLetterPublishingRecoverer republishes it unchanged to
 * "<original-topic>.dlt" instead of blocking the partition forever or
 * silently dropping it. Nothing currently reads the .dlt topics automatically
 * - in a real deployment that's a human-monitored queue (or a dedicated
 * reprocessing job), not a destination this app auto-recovers from on its own.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", record.partition()));

        FixedBackOff backOff = new FixedBackOff(500L, 3L);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}

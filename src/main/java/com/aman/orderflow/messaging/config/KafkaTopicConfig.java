package com.aman.orderflow.messaging.config;

import com.aman.orderflow.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Explicit topic provisioning rather than relying on broker auto-creation -
 * Spring's KafkaAdmin picks up every NewTopic bean and creates (or verifies)
 * it on startup, which is the documented production practice: topics get
 * their partition count and replication factor decided deliberately, not
 * whatever a broker's auto-create default happens to be. A single local
 * broker only supports a replication factor of 1 - a real deployment with
 * multiple brokers would raise this for durability.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.ORDER_EVENTS_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENT_EVENTS_DLT).partitions(1).replicas(1).build();
    }
}

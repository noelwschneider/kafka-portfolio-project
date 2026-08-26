package com.orderfulfillment.common.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Declares the four domain topics explicitly (docs/events/event-catalog.md §2) rather than relying
 * on broker auto-create, so partition count/replication are deterministic regardless of broker
 * defaults. Spring Boot's KafkaAdmin bean (auto-configured from spring.kafka.bootstrap-servers)
 * applies these on startup. */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICATION_FACTOR = 1;

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_EVENTS).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic inventoryEventsTopic() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_EVENTS).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic paymentsEventsTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENTS_EVENTS).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic fulfillmentEventsTopic() {
        return TopicBuilder.name(KafkaTopics.FULFILLMENT_EVENTS).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    // The four dead-letter topics (docs/events/event-catalog.md §2). Declared here for the same
    // reason as the domain topics: a DLQ that only exists because a broker auto-created it on first
    // dead-letter would have broker-default partitioning, and Phase 4's whole point is that the
    // failure path is as real and as deterministic as the happy path. Records are keyed by the
    // original record's key (= orderId), so 3 partitions keeps per-order ordering in the DLQ too.

    @Bean
    public NewTopic ordersDlqTopic() {
        return TopicBuilder.name(KafkaTopics.ORDERS_DLQ).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic inventoryDlqTopic() {
        return TopicBuilder.name(KafkaTopics.INVENTORY_DLQ).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic paymentsDlqTopic() {
        return TopicBuilder.name(KafkaTopics.PAYMENTS_DLQ).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    @Bean
    public NewTopic fulfillmentDlqTopic() {
        return TopicBuilder.name(KafkaTopics.FULFILLMENT_DLQ).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }

    // Scenario Service's own DLQ (issue #41) — see KafkaTopics.SCENARIO_DLQ's javadoc. Declared here
    // rather than only in scenario-service so every service that pulls in services/common agrees on
    // its shape, matching the four domain DLQs above.
    @Bean
    public NewTopic scenarioDlqTopic() {
        return TopicBuilder.name(KafkaTopics.SCENARIO_DLQ).partitions(PARTITIONS).replicas(REPLICATION_FACTOR).build();
    }
}

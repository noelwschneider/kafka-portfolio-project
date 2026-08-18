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
}

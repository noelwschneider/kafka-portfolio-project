package com.orderfulfillment.inventory;

import com.orderfulfillment.common.kafka.ConsumerErrorHandlerFactory;
import com.orderfulfillment.common.kafka.KafkaTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Inventory Service's entire share of the retry/DLQ policy: name the DLQ topic, and let the shared
 * {@link ConsumerErrorHandlerFactory} supply the policy itself (docs/reliability-pattern.md).
 *
 * <p>Spring Boot's Kafka auto-configuration applies a single {@code CommonErrorHandler} bean to the
 * listener container factory, so this one bean covers both of this service's listeners without
 * either of them having to name it. Both dead-letter to {@code inventory.dlq} even though they
 * consume {@code orders.events} and {@code payments.events} respectively: per
 * docs/events/event-catalog.md §2 the DLQ belongs to the failing consumer, not to the publisher of
 * the record it choked on.
 */
@Configuration
public class InventoryKafkaReliabilityConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerErrorHandlerFactory factory) {
        return factory.create(KafkaTopics.INVENTORY_DLQ);
    }
}

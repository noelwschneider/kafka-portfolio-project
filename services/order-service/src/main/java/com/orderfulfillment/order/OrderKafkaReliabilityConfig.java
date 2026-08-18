package com.orderfulfillment.order;

import com.orderfulfillment.common.kafka.ConsumerErrorHandlerFactory;
import com.orderfulfillment.common.kafka.KafkaTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Order Service's entire share of the retry/DLQ policy: name the DLQ topic, and let the shared
 * {@link ConsumerErrorHandlerFactory} supply the policy itself (docs/reliability-pattern.md).
 *
 * <p>Spring Boot's Kafka auto-configuration applies a single {@code CommonErrorHandler} bean to the
 * listener container factory, so this one bean covers all three of this service's listeners without
 * any of them having to name it. All three dead-letter to {@code orders.dlq} regardless of which
 * topic the record arrived on ({@code inventory.events}, {@code payments.events},
 * {@code fulfillment.events}): per docs/events/event-catalog.md §2 the DLQ belongs to the failing
 * consumer, not to the publisher of the record it choked on.
 */
@Configuration
public class OrderKafkaReliabilityConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerErrorHandlerFactory factory) {
        return factory.create(KafkaTopics.ORDERS_DLQ);
    }
}

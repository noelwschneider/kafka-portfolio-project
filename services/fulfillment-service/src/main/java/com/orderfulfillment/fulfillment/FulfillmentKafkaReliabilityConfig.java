package com.orderfulfillment.fulfillment;

import com.orderfulfillment.common.kafka.ConsumerErrorHandlerFactory;
import com.orderfulfillment.common.kafka.KafkaTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Fulfillment Service's entire share of the retry/DLQ policy: name the DLQ topic, and let the shared
 * {@link ConsumerErrorHandlerFactory} supply the policy itself (docs/reliability-pattern.md).
 *
 * <p>Spring Boot's Kafka auto-configuration applies a single {@code CommonErrorHandler} bean to the
 * listener container factory, so this one bean covers this service's one listener without it having
 * to name it.
 */
@Configuration
public class FulfillmentKafkaReliabilityConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerErrorHandlerFactory factory) {
        return factory.create(KafkaTopics.FULFILLMENT_DLQ);
    }
}

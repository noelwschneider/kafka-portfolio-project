package com.orderfulfillment.scenario.projection;

import com.orderfulfillment.common.kafka.ConsumerErrorHandlerFactory;
import com.orderfulfillment.common.kafka.KafkaTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Scenario Service's share of the retry/DLQ policy (issue #41), matching the pattern every domain
 * service already follows (docs/reliability-pattern.md §4.1, {@code OrderKafkaReliabilityConfig} and
 * its three siblings) — this was the one missing piece. Before this bean existed, Spring Boot's
 * Kafka auto-configuration had no {@code CommonErrorHandler} to apply to this service's listener
 * container factory and fell back to Spring Kafka's own framework default
 * ({@code DefaultErrorHandler}'s no-arg constructor: {@code FixedBackOff(0, 9)}, i.e. up to 10
 * immediate redeliveries with no classification and no DLQ), silently bypassing the documented
 * MAX_RETRIES=3 / ~3.5s / DLQ-on-exhaustion policy for both of {@link EventProjectionConsumer}'s
 * listeners. See docs/agent-reports/sprint-7/issue-41-retry-classification.md for how this was found
 * and confirmed.
 *
 * <p>Spring Boot's Kafka auto-configuration applies a single {@code CommonErrorHandler} bean to the
 * listener container factory, so this one bean covers both of {@link EventProjectionConsumer}'s
 * listeners without either naming it.
 */
@Configuration
public class ScenarioKafkaReliabilityConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(ConsumerErrorHandlerFactory factory) {
        return factory.create(KafkaTopics.SCENARIO_DLQ);
    }
}

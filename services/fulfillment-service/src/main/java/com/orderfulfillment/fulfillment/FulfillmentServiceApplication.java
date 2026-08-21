package com.orderfulfillment.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fulfillment Service — Phase 3 extraction from the Phase 1/2 monolith. Runs standalone on port
 * 8084 (docs/openapi/fulfillment-service.yaml), communicating with the other three services only
 * via Kafka (docs/events/event-catalog.md).
 *
 * <p>See {@code com.orderfulfillment.order.OrderServiceApplication}'s Javadoc for why
 * {@code com.orderfulfillment.common} must be listed explicitly in {@code scanBasePackages}.
 *
 * <p>The explicit {@code excludeFilters} restores something declaring {@code @ComponentScan} by
 * hand silently throws away: {@code @SpringBootApplication}'s own scan carries a
 * {@link TypeExcludeFilter}, and that filter is what keeps {@code @TestConfiguration} /
 * {@code @TestComponent} classes on the test classpath from being scanned into the application
 * context of tests that did not ask for them (docs/agent-reports/phase-4-pattern-design.md §4.1).
 */
@SpringBootApplication
@EnableScheduling // Sprint 2: drives OutboxPublisher's poll of outbox_events (ADR-006).
@ComponentScan(
        basePackages = {"com.orderfulfillment.fulfillment", "com.orderfulfillment.common"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class FulfillmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillmentServiceApplication.class, args);
    }
}

package com.orderfulfillment.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order Service — Phase 3 extraction from the Phase 1/2 monolith. Runs standalone on port 8081
 * (docs/openapi/order-service.yaml), communicating with the other three services only via Kafka
 * (docs/events/event-catalog.md).
 *
 * <p>{@code scanBasePackages} includes {@code com.orderfulfillment.common} because that shared
 * library module lives in a sibling package, not a subpackage of this application's own base
 * package — {@code @SpringBootApplication}'s default component scan only covers the annotated
 * class's own package and below.
 *
 * <p>The explicit {@code excludeFilters} restores something declaring {@code @ComponentScan} by
 * hand silently throws away: {@code @SpringBootApplication}'s own scan carries a
 * {@link TypeExcludeFilter}, and that filter is what keeps {@code @TestConfiguration} /
 * {@code @TestComponent} classes on the test classpath from being scanned into the application
 * context of tests that did not ask for them (see Inventory Service's identical fix,
 * docs/agent-reports/phase-4-pattern-design.md §4.1). Without it, a test fixture defined for one
 * test class would silently pollute every other test's context.
 */
@SpringBootApplication
@EnableScheduling // Phase 6: drives OutboxPublisher's poll of outbox_events (ADR-006).
@ComponentScan(
        basePackages = {"com.orderfulfillment.order", "com.orderfulfillment.common"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

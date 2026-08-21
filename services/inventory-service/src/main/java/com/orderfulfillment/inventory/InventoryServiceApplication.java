package com.orderfulfillment.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Inventory Service — Phase 3 extraction from the Phase 1/2 monolith. Runs standalone on port 8082
 * (docs/openapi/inventory-service.yaml), communicating with the other three services only via
 * Kafka (docs/events/event-catalog.md).
 *
 * <p>See {@code com.orderfulfillment.order.OrderServiceApplication}'s Javadoc for why
 * {@code com.orderfulfillment.common} must be listed explicitly in {@code scanBasePackages}.
 *
 * <p>The explicit {@code excludeFilters} restores something declaring {@code @ComponentScan}
 * by hand silently throws away: {@code @SpringBootApplication}'s own scan carries a
 * {@link TypeExcludeFilter}, and that filter is what keeps {@code @TestConfiguration} /
 * {@code @TestComponent} classes on the test classpath from being scanned into the application
 * context of tests that did not ask for them. Without it, a test fixture defined for one test class
 * — Phase 4 added one, a deliberately-failing Kafka listener — is loaded into <em>every</em> test's
 * context and shows up in, for instance, {@code GET /demo/consumers}.
 */
@SpringBootApplication
@EnableScheduling // Sprint 2: drives OutboxPublisher's poll of outbox_events (ADR-006).
@ComponentScan(
        basePackages = {"com.orderfulfillment.inventory", "com.orderfulfillment.common"},
        excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

package com.orderfulfillment.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Order Service — Phase 3 extraction from the Phase 1/2 monolith. Runs standalone on port 8081
 * (docs/openapi/order-service.yaml), communicating with the other three services only via Kafka
 * (docs/events/event-catalog.md).
 *
 * <p>{@code scanBasePackages} includes {@code com.orderfulfillment.common} because that shared
 * library module lives in a sibling package, not a subpackage of this application's own base
 * package — {@code @SpringBootApplication}'s default component scan only covers the annotated
 * class's own package and below.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.orderfulfillment.order", "com.orderfulfillment.common"})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

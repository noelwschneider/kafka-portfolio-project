package com.orderfulfillment.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Inventory Service — Phase 3 extraction from the Phase 1/2 monolith. Runs standalone on port 8082
 * (docs/openapi/inventory-service.yaml), communicating with the other three services only via
 * Kafka (docs/events/event-catalog.md).
 *
 * <p>See {@code com.orderfulfillment.order.OrderServiceApplication}'s Javadoc for why
 * {@code com.orderfulfillment.common} must be listed explicitly in {@code scanBasePackages}.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.orderfulfillment.inventory", "com.orderfulfillment.common"})
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

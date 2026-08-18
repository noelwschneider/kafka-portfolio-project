package com.orderfulfillment.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Fulfillment Service — Phase 3 extraction from the Phase 1/2 monolith. Runs standalone on port
 * 8084 (docs/openapi/fulfillment-service.yaml), communicating with the other three services only
 * via Kafka (docs/events/event-catalog.md).
 *
 * <p>See {@code com.orderfulfillment.order.OrderServiceApplication}'s Javadoc for why
 * {@code com.orderfulfillment.common} must be listed explicitly in {@code scanBasePackages}.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.orderfulfillment.fulfillment", "com.orderfulfillment.common"})
public class FulfillmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillmentServiceApplication.class, args);
    }
}

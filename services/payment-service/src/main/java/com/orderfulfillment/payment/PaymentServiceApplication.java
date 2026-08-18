package com.orderfulfillment.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Payment Service — Phase 3 extraction from the Phase 1/2 monolith. Runs standalone on port 8083
 * (docs/openapi/payment-service.yaml), communicating with the other three services only via Kafka
 * (docs/events/event-catalog.md).
 *
 * <p>See {@code com.orderfulfillment.order.OrderServiceApplication}'s Javadoc for why
 * {@code com.orderfulfillment.common} must be listed explicitly in {@code scanBasePackages}.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.orderfulfillment.payment", "com.orderfulfillment.common"})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}

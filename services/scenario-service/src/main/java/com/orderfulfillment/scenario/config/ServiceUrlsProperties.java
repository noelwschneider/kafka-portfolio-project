package com.orderfulfillment.scenario.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Base URLs for the other four services' public APIs, per the OpenAPI doc's "How a run is composed". */
@ConfigurationProperties(prefix = "orderfulfillment.services")
public record ServiceUrlsProperties(
        String orderService,
        String inventoryService,
        String paymentService,
        String fulfillmentService
) {
}

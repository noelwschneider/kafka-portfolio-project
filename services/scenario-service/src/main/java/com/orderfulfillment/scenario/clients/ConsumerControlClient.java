package com.orderfulfillment.scenario.clients;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Wraps the identical {@code /demo/consumers/{name}/pause|resume} shape Inventory and Fulfillment
 * Service both expose (docs/openapi/inventory-service.yaml, docs/openapi/fulfillment-service.yaml).
 */
@Component
public class ConsumerControlClient {

    private final RestClient inventoryClient;
    private final RestClient fulfillmentClient;

    public ConsumerControlClient(RestClient inventoryServiceRestClient, RestClient fulfillmentServiceRestClient) {
        this.inventoryClient = inventoryServiceRestClient;
        this.fulfillmentClient = fulfillmentServiceRestClient;
    }

    public int pauseInventoryConsumer(String consumerName) {
        return pause(inventoryClient, consumerName);
    }

    public int resumeInventoryConsumer(String consumerName) {
        return resume(inventoryClient, consumerName);
    }

    public int pauseFulfillmentConsumer(String consumerName) {
        return pause(fulfillmentClient, consumerName);
    }

    public int resumeFulfillmentConsumer(String consumerName) {
        return resume(fulfillmentClient, consumerName);
    }

    /** Lists paused consumers across both services, as {@code service/listener} — for /demo/reset. */
    @SuppressWarnings("unchecked")
    public java.util.List<String> pausedConsumers() {
        java.util.List<String> paused = new java.util.ArrayList<>();
        paused.addAll(pausedOn(inventoryClient, "inventory-service"));
        paused.addAll(pausedOn(fulfillmentClient, "fulfillment-service"));
        return paused;
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> pausedOn(RestClient client, String serviceName) {
        try {
            java.util.List<Map<String, Object>> consumers = client.get()
                    .uri("/demo/consumers")
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
            if (consumers == null) {
                return java.util.List.of();
            }
            return consumers.stream()
                    .filter(c -> Boolean.TRUE.equals(c.get("paused")))
                    .map(c -> serviceName + "/" + c.get("name"))
                    .toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private int pause(RestClient client, String consumerName) {
        return client.post().uri("/demo/consumers/{name}/pause", consumerName)
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private int resume(RestClient client, String consumerName) {
        return client.post().uri("/demo/consumers/{name}/resume", consumerName)
                .retrieve().toBodilessEntity().getStatusCode().value();
    }
}

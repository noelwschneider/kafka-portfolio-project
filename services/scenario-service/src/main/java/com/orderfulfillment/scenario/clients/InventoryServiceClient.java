package com.orderfulfillment.scenario.clients;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Wraps Inventory Service's {@code PUT /api/inventory/{sku}} setup/demo-administration endpoint. */
@Component
public class InventoryServiceClient {

    private final RestClient client;

    public InventoryServiceClient(RestClient inventoryServiceRestClient) {
        this.client = inventoryServiceRestClient;
    }

    public void setAvailableQuantity(String sku, int quantity) {
        client.put()
                .uri("/api/inventory/{sku}", sku)
                .body(Map.of("availableQuantity", quantity))
                .retrieve()
                .toBodilessEntity();
    }
}

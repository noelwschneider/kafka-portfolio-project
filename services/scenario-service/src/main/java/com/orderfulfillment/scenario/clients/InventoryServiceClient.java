package com.orderfulfillment.scenario.clients;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Wraps Inventory Service's {@code /demo/inventory/{sku}/restore} endpoint
 * (docs/openapi/inventory-service.yaml), used by {@code DemoResetService} to bring a SKU back to its
 * seed state. Deliberately not {@code PUT /api/inventory/{sku}}: that production endpoint only ever
 * sets {@code availableQuantity} and rejects a value below the SKU's current {@code reservedQuantity}
 * — it structurally cannot clear the reservation ledger a long-running demo accumulates. The
 * {@code /demo} endpoint sets both fields atomically instead.
 */
@Component
public class InventoryServiceClient {

    private final RestClient client;

    public InventoryServiceClient(RestClient inventoryServiceRestClient) {
        this.client = inventoryServiceRestClient;
    }

    public void restoreInventory(String sku, int seedAvailableQuantity) {
        client.post()
                .uri("/demo/inventory/{sku}/restore", sku)
                .body(Map.of("availableQuantity", seedAvailableQuantity))
                .retrieve()
                .toBodilessEntity();
    }
}

package com.orderfulfillment.inventory;

import com.orderfulfillment.inventory.dto.InventoryItemDto;
import com.orderfulfillment.inventory.dto.RestoreInventoryRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/openapi/inventory-service.yaml's {@code /demo} namespace, isolated from {@code /api}
 * (agent-guidance.md rule 9) same as {@link DemoConsumerController}. Called only by Scenario
 * Service's {@code DemoResetService}, over cluster-internal DNS — like {@code /demo/consumers}, this
 * path is deliberately absent from the production ingress allowlist
 * (infrastructure/kubernetes/production/common/ingress.yaml).
 *
 * <p>Exists because {@code PUT /api/inventory/{sku}} structurally cannot restore a demo to a clean
 * state: it only ever sets {@code availableQuantity} and rejects any value below the SKU's current
 * {@code reservedQuantity}, but reservations are never released on the successful-fulfillment path,
 * so a long-running demo's {@code reservedQuantity} routinely exceeds the seed value by the time a
 * reset is needed (docs/agent-reports/sprint-2/deployment-execution-report.md §6). This endpoint sets
 * both fields atomically so reset always succeeds.
 */
@RestController
@RequestMapping("/demo/inventory")
public class DemoInventoryController {

    private final InventoryService inventoryService;

    public DemoInventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/{sku}/restore")
    public InventoryItemDto restore(@PathVariable String sku, @Valid @RequestBody RestoreInventoryRequest request) {
        return inventoryService.restoreForDemo(sku, request.availableQuantity());
    }
}

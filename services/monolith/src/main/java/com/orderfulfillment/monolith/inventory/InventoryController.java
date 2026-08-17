package com.orderfulfillment.monolith.inventory;

import com.orderfulfillment.monolith.inventory.dto.InventoryItemDto;
import com.orderfulfillment.monolith.inventory.dto.UpdateInventoryRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/inventory-service.yaml's /api namespace — production-style stock administration. */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public List<InventoryItemDto> listInventory() {
        return inventoryService.listAll();
    }

    @GetMapping("/{sku}")
    public InventoryItemDto getInventoryItem(@PathVariable String sku) {
        return inventoryService.getBySku(sku);
    }

    @PutMapping("/{sku}")
    public InventoryItemDto updateInventoryItem(@PathVariable String sku, @Valid @RequestBody UpdateInventoryRequest request) {
        return inventoryService.updateAvailableQuantity(sku, request.availableQuantity());
    }
}

package com.orderfulfillment.inventory.dto;

import java.time.Instant;

public record InventoryItemDto(
        String sku,
        String displayName,
        int availableQuantity,
        int reservedQuantity,
        long version,
        Instant updatedAt
) {
}

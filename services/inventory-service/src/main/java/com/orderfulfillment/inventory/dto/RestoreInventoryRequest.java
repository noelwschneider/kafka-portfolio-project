package com.orderfulfillment.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /demo/inventory/{sku}/restore} body. Unlike {@link UpdateInventoryRequest}, this always
 * clears {@code reservedQuantity} to zero alongside setting {@code availableQuantity} — see
 * {@code InventoryService#restoreForDemo} for why that has to happen atomically and outside the
 * {@code availableQuantity >= reservedQuantity} guard the production PUT enforces.
 */
public record RestoreInventoryRequest(
        @NotNull @Min(0) @Max(10000) Integer availableQuantity
) {
}

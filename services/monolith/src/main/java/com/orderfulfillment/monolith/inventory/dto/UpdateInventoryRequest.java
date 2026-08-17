package com.orderfulfillment.monolith.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateInventoryRequest(
        @NotNull @Min(0) @Max(10000) Integer availableQuantity
) {
}

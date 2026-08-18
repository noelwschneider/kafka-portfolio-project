package com.orderfulfillment.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateOrderItem(
        @NotNull @Pattern(regexp = "^SKU-[0-9]{3}$") String sku,
        @NotNull @Min(1) @Max(100) Integer quantity
) {
}

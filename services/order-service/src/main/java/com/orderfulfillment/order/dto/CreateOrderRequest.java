package com.orderfulfillment.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
        @NotNull @Size(min = 1, max = 64) String customerId,
        @NotEmpty @Size(min = 1, max = 20) @Valid List<CreateOrderItem> items
) {
}

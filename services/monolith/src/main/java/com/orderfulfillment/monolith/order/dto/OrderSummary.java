package com.orderfulfillment.monolith.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummary(
        String id,
        String customerId,
        String status,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt
) {
}

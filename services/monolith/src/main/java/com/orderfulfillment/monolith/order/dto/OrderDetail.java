package com.orderfulfillment.monolith.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderDetail(
        String id,
        String customerId,
        String status,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt,
        List<OrderItemDto> items,
        List<OrderStatusHistoryEntryDto> statusHistory
) {
}

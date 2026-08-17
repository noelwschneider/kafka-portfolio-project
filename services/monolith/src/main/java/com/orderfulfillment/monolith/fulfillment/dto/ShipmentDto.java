package com.orderfulfillment.monolith.fulfillment.dto;

import java.time.Instant;

public record ShipmentDto(
        String id,
        String orderId,
        String status,
        String trackingNumber,
        Instant createdAt,
        Instant updatedAt
) {
}

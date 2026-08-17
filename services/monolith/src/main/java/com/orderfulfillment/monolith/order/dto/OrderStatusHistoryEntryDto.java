package com.orderfulfillment.monolith.order.dto;

import java.time.Instant;
import java.util.UUID;

public record OrderStatusHistoryEntryDto(String status, UUID sourceEventId, Instant occurredAt) {
}

package com.orderfulfillment.monolith.order.dto;

import java.time.Instant;

public record OrderAccepted(String id, String status, Instant createdAt) {
}

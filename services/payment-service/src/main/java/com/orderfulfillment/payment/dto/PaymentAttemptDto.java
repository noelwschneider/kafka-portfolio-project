package com.orderfulfillment.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentAttemptDto(
        String id,
        String orderId,
        String status,
        BigDecimal amount,
        String failureReason,
        UUID idempotencyKey,
        Instant createdAt,
        Instant updatedAt
) {
}

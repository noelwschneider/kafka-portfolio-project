package com.orderfulfillment.common.events;

import java.math.BigDecimal;
import java.time.Instant;

/** docs/events/event-catalog.md §3 — PaymentRejected. Published by Payment Service on {@code payments.events}. */
public record PaymentRejectedPayload(String orderId, String paymentAttemptId, BigDecimal amount, String failureReason, Instant rejectedAt) {
}

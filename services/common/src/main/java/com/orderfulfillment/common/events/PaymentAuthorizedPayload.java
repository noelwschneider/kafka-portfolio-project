package com.orderfulfillment.common.events;

import java.math.BigDecimal;
import java.time.Instant;

/** docs/events/event-catalog.md §3 — PaymentAuthorized. Published by Payment Service on {@code payments.events}.
 * The project's one deliberate fan-out event: consumed independently by Order Service and Fulfillment Service. */
public record PaymentAuthorizedPayload(String orderId, String paymentAttemptId, BigDecimal amount, Instant authorizedAt) {
}

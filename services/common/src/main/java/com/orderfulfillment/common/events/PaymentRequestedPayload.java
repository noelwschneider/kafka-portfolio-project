package com.orderfulfillment.common.events;

import java.math.BigDecimal;
import java.util.UUID;

/** docs/events/event-catalog.md §3 — PaymentRequested. Published by ORDER Service (not Payment
 * Service — deliberate, see the catalog's §2 rationale) on {@code orders.events}. */
public record PaymentRequestedPayload(String orderId, BigDecimal amount, UUID idempotencyKey) {
}

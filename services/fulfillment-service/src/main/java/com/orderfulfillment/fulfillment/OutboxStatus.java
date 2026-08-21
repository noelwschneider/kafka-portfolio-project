package com.orderfulfillment.fulfillment;

/**
 * Lifecycle of one {@code outbox_events} row (docs/db-ownership.md §2). Fulfillment Service's copy
 * of Order Service's {@code OutboxStatus} pattern (ADR-006), added in Sprint 2 to close the
 * dual-write gap ADR-006 originally left open for this service.
 */
enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}

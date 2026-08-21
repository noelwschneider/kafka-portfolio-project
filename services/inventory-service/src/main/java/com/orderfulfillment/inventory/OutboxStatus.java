package com.orderfulfillment.inventory;

/**
 * Lifecycle of one {@code outbox_events} row (docs/db-ownership.md §2). Stored as text, like every
 * other enum in this schema. Mirrors Order Service's {@code OutboxStatus} (ADR-006) — see that
 * class's Javadoc for the full rationale; this is Inventory Service's own copy of the same pattern,
 * extended here in Sprint 2 to close the dual-write gap ADR-006 originally left open for this
 * service.
 *
 * <p>{@code FAILED} is a real, reachable state, not a placeholder: see {@link OutboxDispatcher} for
 * the exact policy that moves a row there rather than leaving it PENDING forever. A FAILED row is
 * an event that was never published and never will be by this service — it needs a human.
 */
enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}

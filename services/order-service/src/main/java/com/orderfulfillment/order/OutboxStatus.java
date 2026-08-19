package com.orderfulfillment.order;

/**
 * Lifecycle of one {@code outbox_events} row (docs/db-ownership.md §2). Stored as text, like every
 * other enum in this schema.
 *
 * <p>{@code FAILED} is a real, reachable state, not a placeholder: see {@link OutboxDispatcher} for
 * the exact policy that moves a row there rather than leaving it PENDING forever. A FAILED row is
 * an event that was never published and never will be by this service — it needs a human
 * (ADR-006's "a status column whose FAILED rows need someone to look at them").
 */
enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}

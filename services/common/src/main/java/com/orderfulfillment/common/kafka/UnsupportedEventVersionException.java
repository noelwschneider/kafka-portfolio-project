package com.orderfulfillment.common.kafka;

/** docs/events/event-catalog.md §5: "A consumer that receives an eventVersion it does not know
 * must treat it as a non-retryable failure and route the record to its DLQ." No DLQ exists yet
 * (Phase 4), so this phase's behavior is to fail loudly instead of silently swallowing the record —
 * per this phase's "no idempotency, no outbox, no DLQ yet" rule. */
public class UnsupportedEventVersionException extends RuntimeException {

    public UnsupportedEventVersionException(String eventType, int eventVersion) {
        super("Unsupported eventVersion %d for eventType %s".formatted(eventVersion, eventType));
    }
}

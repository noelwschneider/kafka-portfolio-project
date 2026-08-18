package com.orderfulfillment.common.kafka;

/** docs/events/event-catalog.md §2 — frozen topic ownership table. A service publishes only to its
 * own domain topic; this class is the single place topic name strings live. */
public final class KafkaTopics {

    public static final String ORDERS_EVENTS = "orders.events";
    public static final String INVENTORY_EVENTS = "inventory.events";
    public static final String PAYMENTS_EVENTS = "payments.events";
    public static final String FULFILLMENT_EVENTS = "fulfillment.events";

    /**
     * Dead-letter topics, one per consuming service — routing targets, not domain event types
     * (docs/events/event-catalog.md §2: "Records that exhausted retries, plus failure metadata",
     * published by "the failing consumer"). A service dead-letters to its <em>own</em> domain's DLQ
     * regardless of which topic the failing record came from: Inventory Service consumes
     * {@code orders.events} and {@code payments.events}, and both dead-letter to
     * {@code inventory.dlq}, because the failure belongs to the consumer, not to the publisher.
     * See docs/reliability-pattern.md.
     */
    public static final String ORDERS_DLQ = "orders.dlq";
    public static final String INVENTORY_DLQ = "inventory.dlq";
    public static final String PAYMENTS_DLQ = "payments.dlq";
    public static final String FULFILLMENT_DLQ = "fulfillment.dlq";

    private KafkaTopics() {
    }
}

package com.orderfulfillment.common.kafka;

/** docs/events/event-catalog.md §2 — frozen topic ownership table. A service publishes only to its
 * own domain topic; this class is the single place topic name strings live. */
public final class KafkaTopics {

    public static final String ORDERS_EVENTS = "orders.events";
    public static final String INVENTORY_EVENTS = "inventory.events";
    public static final String PAYMENTS_EVENTS = "payments.events";
    public static final String FULFILLMENT_EVENTS = "fulfillment.events";

    private KafkaTopics() {
    }
}

package com.orderfulfillment.common.kafka;

/** docs/events/event-catalog.md §3 — the frozen {@code eventType} strings. */
public final class EventTypes {

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String INVENTORY_RESERVED = "InventoryReserved";
    public static final String INVENTORY_RESERVATION_FAILED = "InventoryReservationFailed";
    public static final String INVENTORY_RELEASED = "InventoryReleased";
    public static final String PAYMENT_REQUESTED = "PaymentRequested";
    public static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";
    public static final String PAYMENT_REJECTED = "PaymentRejected";
    public static final String SHIPMENT_CREATED = "ShipmentCreated";

    /** Every eventType in this catalog is at eventVersion 1 (event-catalog.md §5). */
    public static final int CURRENT_VERSION = 1;

    private EventTypes() {
    }
}

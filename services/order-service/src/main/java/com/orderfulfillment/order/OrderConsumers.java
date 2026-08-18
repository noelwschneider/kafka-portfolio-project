package com.orderfulfillment.order;

/**
 * The three logical names Order Service's consumers are known by, in the two places a name is
 * load-bearing — mirrors {@code InventoryConsumers} (docs/reliability-pattern.md §8 point 3).
 *
 * <p>Unlike Inventory Service, Order Service's OpenAPI document
 * (docs/openapi/order-service.yaml) does not define {@code /demo/consumers}, so the listener id
 * has no frozen contract to match. It still has to be a stable compile-time constant, both for
 * logging and because a hand-written id is what {@code @KafkaListener} needs to be addressable at
 * all (e.g. via {@code KafkaListenerEndpointRegistry}, should a later phase add the endpoint).
 *
 * <p>The two namespaces are kept apart on purpose, per the same reasoning as
 * {@code InventoryConsumers}:
 * <ul>
 *   <li>the <b>listener id</b> is the {@code @KafkaListener} id — one per inbound topic, since each
 *       of Order Service's three listeners subscribes to a different topic;</li>
 *   <li>the <b>consumer name</b> is the {@code processed_events.consumer_name} column, qualified by
 *       service ({@code "order.inventory-events"}, etc.). Each listener method handles more than one
 *       {@code eventType} on its topic (e.g. {@code InventoryReserved} and
 *       {@code InventoryReservationFailed}), and per docs/reliability-pattern.md §8 point 3 that is
 *       still exactly one {@code consumer_name} per listener method, not one per event type — the
 *       composite ledger key already disambiguates by {@code eventId}.</li>
 * </ul>
 *
 * <p>Both are compile-time constants and must never be derived from anything that varies between
 * restarts: a ledger row written under one name and looked up under another would not deduplicate.
 */
final class OrderConsumers {

    static final String INVENTORY_EVENTS_LISTENER_ID = "inventory-events";
    static final String PAYMENT_EVENTS_LISTENER_ID = "payment-events";
    static final String FULFILLMENT_EVENTS_LISTENER_ID = "fulfillment-events";

    static final String INVENTORY_EVENTS_CONSUMER = "order.inventory-events";
    static final String PAYMENT_EVENTS_CONSUMER = "order.payment-events";
    static final String FULFILLMENT_EVENTS_CONSUMER = "order.fulfillment-events";

    private OrderConsumers() {
    }
}

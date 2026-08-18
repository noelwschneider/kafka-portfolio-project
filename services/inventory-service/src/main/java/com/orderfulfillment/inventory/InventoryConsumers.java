package com.orderfulfillment.inventory;

/**
 * The two logical names Inventory Service's consumers are known by, in the two places a name is
 * load-bearing.
 *
 * <p>They are kept together because the two namespaces must stay in step but must not be conflated:
 * <ul>
 *   <li>the <b>listener id</b> is the {@code @KafkaListener} id, the key in
 *       {@code KafkaListenerEndpointRegistry}, and the {@code consumerName} path variable of
 *       {@code /demo/consumers/{consumerName}/pause} — it matches the frozen example in
 *       docs/openapi/inventory-service.yaml ({@code order-created}, {@code payment-rejected});</li>
 *   <li>the <b>consumer name</b> is the {@code processed_events.consumer_name} column, qualified by
 *       service per ADR-005's own example ({@code "inventory.order-created"}), because that column
 *       identifies a consumer within a ledger whose rows outlive any one deployment.</li>
 * </ul>
 *
 * <p>Both are compile-time constants and must never be derived from anything that varies between
 * restarts: a ledger row written under one name and looked up under another would not deduplicate.
 */
final class InventoryConsumers {

    static final String ORDER_CREATED_LISTENER_ID = "order-created";
    static final String PAYMENT_REJECTED_LISTENER_ID = "payment-rejected";

    static final String ORDER_CREATED_CONSUMER = "inventory.order-created";
    static final String PAYMENT_REJECTED_CONSUMER = "inventory.payment-rejected";

    private InventoryConsumers() {
    }
}

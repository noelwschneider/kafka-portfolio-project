package com.orderfulfillment.fulfillment;

/**
 * The name Fulfillment Service's one consumer is known by, in the two places a name is load-bearing
 * (mirrors Inventory Service's {@code InventoryConsumers}).
 *
 * <p>The two namespaces must stay in step but must not be conflated:
 * <ul>
 *   <li>the <b>listener id</b> is the {@code @KafkaListener} id, the key in
 *       {@code KafkaListenerEndpointRegistry}, and the {@code consumerName} path variable of
 *       {@code /demo/consumers/{consumerName}/pause} — it matches the frozen example in
 *       docs/openapi/fulfillment-service.yaml ({@code payment-authorized});</li>
 *   <li>the <b>consumer name</b> is the {@code processed_events.consumer_name} column, qualified by
 *       service per ADR-005's own example ({@code "fulfillment.payment-authorized"}), because that
 *       column identifies a consumer within a ledger whose rows outlive any one deployment.</li>
 * </ul>
 *
 * <p>Both are compile-time constants and must never be derived from anything that varies between
 * restarts: a ledger row written under one name and looked up under another would not deduplicate.
 */
final class FulfillmentConsumers {

    static final String PAYMENT_AUTHORIZED_LISTENER_ID = "payment-authorized";

    static final String PAYMENT_AUTHORIZED_CONSUMER = "fulfillment.payment-authorized";

    private FulfillmentConsumers() {
    }
}

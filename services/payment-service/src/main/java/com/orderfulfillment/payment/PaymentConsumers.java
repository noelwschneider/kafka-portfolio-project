package com.orderfulfillment.payment;

/**
 * The logical name Payment Service's one {@code @KafkaListener} is known by, in the two places a
 * name is load-bearing. Mirrors {@code InventoryConsumers} (docs/reliability-pattern.md §8, point
 * 3), even though Payment Service has no {@code /demo/consumers} endpoint to expose the listener
 * id over — the id is still required on {@code @KafkaListener} for logging/consistency, and the
 * shape is kept identical to the other three services rather than invented differently here.
 *
 * <ul>
 *   <li>the <b>listener id</b> is the {@code @KafkaListener} id and the key in
 *       {@code KafkaListenerEndpointRegistry}. Payment Service's OpenAPI document does not define
 *       {@code /demo/consumers}, so nothing addresses this name over HTTP today — it exists purely
 *       for log correlation and parity with the other services;</li>
 *   <li>the <b>consumer name</b> is the {@code processed_events.consumer_name} column, qualified by
 *       service per ADR-005's own example ({@code "payment.payment-requested"}), because that
 *       column identifies a consumer within a ledger whose rows outlive any one deployment.</li>
 * </ul>
 *
 * <p>Both are compile-time constants and must never be derived from anything that varies between
 * restarts: a ledger row written under one name and looked up under another would not deduplicate.
 */
final class PaymentConsumers {

    static final String PAYMENT_REQUESTED_LISTENER_ID = "payment-requested";

    static final String PAYMENT_REQUESTED_CONSUMER = "payment.payment-requested";

    private PaymentConsumers() {
    }
}

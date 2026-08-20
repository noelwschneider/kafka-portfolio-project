package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.InventoryReservedPayload;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.ShipmentCreatedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import com.orderfulfillment.order.dto.OrderDetail;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Regression test for the defect found live in docs/agent-reports/phase-10-scaling-demo.md §4 and
 * fixed by docs/adr/ADR-009-out-of-order-status-transitions.md.
 *
 * <p><b>The original failure.</b> {@code PaymentAuthorized} fans out to two independent consumer
 * groups (docs/events/event-catalog.md §3). Under load, Fulfillment Service processed it and
 * published {@code ShipmentCreated} more than seven seconds before Order Service's own
 * {@link OrderPaymentEventsConsumer} got to the same record. Order Service therefore consumed
 * {@code ShipmentCreated} first and wrote {@code FULFILLED} straight out of {@code PAYMENT_PENDING}
 * — an invalid transition — and the late {@code PaymentAuthorized} then unconditionally overwrote
 * that terminal state back to {@code PAID} → {@code FULFILLMENT_PENDING}, stranding the order there
 * forever. Observed history: {@code PENDING → INVENTORY_RESERVED → PAYMENT_PENDING → FULFILLED →
 * PAID → FULFILLMENT_PENDING}.
 *
 * <p><b>Why this test is deterministic.</b> It does not hope the race happens; it <em>causes</em>
 * it, in the exact order that produced the bug. The two events are published through real
 * Testcontainers Kafka and consumed by the real listeners, but the test only publishes
 * {@code PaymentAuthorized} after it has observed — in {@code deferred_transitions} — that the early
 * {@code ShipmentCreated} has already been fully processed. There is no sleep and no timing
 * assumption: the race window is created by construction.
 */
class OrderOutOfOrderTransitionIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> HAPPY_PATH = List.of(
            "PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAID", "FULFILLMENT_PENDING", "FULFILLED");

    @Test
    void shipmentCreatedArrivingBeforePaymentAuthorizedStillEndsFulfilledWithAValidHistory() {
        OrderAccepted accepted = createOrder();
        String orderId = accepted.id();

        publishInventoryReserved(orderId, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("PAYMENT_PENDING"));

        // The race, caused rather than hoped for: ShipmentCreated first.
        UUID shipmentEventId = UUID.randomUUID();
        publishShipmentCreated(orderId, shipmentEventId);

        // Wait for it to be *fully processed* — parked, not applied. This is the synchronization
        // point that makes the ordering deterministic.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(deferredRows(orderId)).hasSize(1));

        // Before the fix this assertion failed here: FULFILLED had already been written straight out
        // of PAYMENT_PENDING, skipping PAID and FULFILLMENT_PENDING.
        OrderDetail beforePayment = getOrder(orderId);
        assertThat(beforePayment.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(beforePayment.statusHistory()).extracting("status")
                .containsExactly("PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING");
        assertThat(deferredRows(orderId).getFirst())
                .containsEntry("target_status", "FULFILLED")
                .containsEntry("status", "PENDING");

        // Now the delayed PaymentAuthorized — the one that used to stomp FULFILLED.
        publishPaymentAuthorized(orderId, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("FULFILLED"));

        // Proving a non-event: the terminal state must *stay* terminal. Held continuously rather
        // than sampled once, since the original bug's reversion arrived after FULFILLED was already
        // visible.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            OrderDetail detail = getOrder(orderId);
            assertThat(detail.status()).isEqualTo("FULFILLED");
            assertThat(detail.statusHistory()).extracting("status").containsExactlyElementsOf(HAPPY_PATH);
        });

        OrderDetail finalDetail = getOrder(orderId);

        // Every transition still produced exactly one history row, in the order
        // docs/order-state-machine.md §3 requires — the skipped PAID / FULFILLMENT_PENDING steps are
        // durably recorded, not silently dropped, and they are recorded *before* FULFILLED.
        assertThat(finalDetail.statusHistory()).extracting("status").containsExactlyElementsOf(HAPPY_PATH);

        // Attribution survives the deferral: the FULFILLED row still carries the ShipmentCreated
        // envelope's eventId, not a synthesized one and not null.
        assertThat(finalDetail.statusHistory().getLast().sourceEventId()).isEqualTo(shipmentEventId);

        // And the parked row is resolved, not left looking pending forever.
        assertThat(deferredRows(orderId).getFirst()).containsEntry("status", "APPLIED");
    }

    /**
     * The other half of the guard, asserted directly: an event for an earlier transition that
     * arrives after a terminal state has been reached must not revert it. Correctness beats "whoever
     * arrives last wins" — docs/order-state-machine.md §3, "no transition leaves a terminal state".
     *
     * <p>Uses a fresh {@code eventId} on purpose, so this is genuinely the guard being tested and not
     * ADR-005's idempotency ledger quietly absorbing a duplicate.
     */
    @Test
    void aLatePaymentAuthorizedCannotRevertAnAlreadyFulfilledOrder() {
        OrderAccepted accepted = createOrder();
        String orderId = accepted.id();

        publishInventoryReserved(orderId, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("PAYMENT_PENDING"));

        publishPaymentAuthorized(orderId, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("FULFILLMENT_PENDING"));

        publishShipmentCreated(orderId, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("FULFILLED"));

        // A second, distinct PaymentAuthorized — a redelivery under a new envelope id, or simply an
        // extremely late one. Before ADR-009 this wrote PAID then FULFILLMENT_PENDING over the top.
        publishPaymentAuthorized(orderId, UUID.randomUUID());

        await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            OrderDetail detail = getOrder(orderId);
            assertThat(detail.status()).isEqualTo("FULFILLED");
            assertThat(detail.statusHistory()).extracting("status").containsExactlyElementsOf(HAPPY_PATH);
        });

        // Nothing was parked either: a stale transition is dropped, not queued to be applied later.
        assertThat(deferredRows(orderId)).isEmpty();
    }

    private List<Map<String, Object>> deferredRows(String orderId) {
        return jdbcClient.sql("SELECT target_status, status, source_event_id FROM "
                        + "order_service.deferred_transitions WHERE order_id = ? ORDER BY id")
                .param(orderId)
                .query()
                .listOfRows();
    }

    private void publishInventoryReserved(String orderId, UUID eventId) {
        InventoryReservedPayload payload = new InventoryReservedPayload(
                orderId, "resv-race-test", List.of(new EventItem("SKU-001", 1)), Instant.now());
        publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, orderId, eventId, payload);
    }

    private void publishPaymentAuthorized(String orderId, UUID eventId) {
        PaymentAuthorizedPayload payload = new PaymentAuthorizedPayload(
                orderId, "pay-race-test", new BigDecimal("10.00"), Instant.now());
        publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_AUTHORIZED, orderId, eventId, payload);
    }

    private void publishShipmentCreated(String orderId, UUID eventId) {
        ShipmentCreatedPayload payload = new ShipmentCreatedPayload(
                orderId, "shp-race-test", "TRK-RACE-TEST", Instant.now());
        publish(KafkaTopics.FULFILLMENT_EVENTS, EventTypes.SHIPMENT_CREATED, orderId, eventId, payload);
    }

    private void publish(String topic, String eventType, String orderId, UUID eventId, Object payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                eventPublisher.publish(topic, eventType, orderId, eventId, payload));
    }

    private OrderAccepted createOrder() {
        CreateOrderRequest request = new CreateOrderRequest("demo-customer",
                List.of(new CreateOrderItem("SKU-001", 1)));
        return client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderAccepted.class)
                .returnResult().getResponseBody();
    }

    private OrderDetail getOrder(String orderId) {
        return client.get().uri("/api/orders/" + orderId).exchange()
                .expectBody(OrderDetail.class).returnResult().getResponseBody();
    }
}

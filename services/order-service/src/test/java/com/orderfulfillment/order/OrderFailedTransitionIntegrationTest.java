package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.InventoryReservedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import com.orderfulfillment.order.dto.OrderDetail;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Sprint 2 goal 2, item 2 — docs/order-state-machine.md transition 9 (any non-terminal →
 * {@code FAILED}), implemented by {@link OrderDeadLetterConsumer} /
 * {@link OrderPersistence#markFailed}. Before this, ADR-009's "Accepted costs" section named this
 * gap explicitly: a dead-lettered record for an order left it stuck at whatever status it last
 * reached, with nothing recording that anything had gone wrong.
 *
 * <p>Every poison record here is put on the real topic as real bytes (same technique as
 * {@code OrderPoisonMessageIntegrationTest}), so the real listener, the real shared error handler,
 * and the real {@code orders.dlq} recovery path all run — nothing about the failure path is
 * simulated.
 */
class OrderFailedTransitionIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aDeadLetteredEventMovesItsOrderToFailed() {
        OrderAccepted accepted = createOrder();
        String orderId = accepted.id();

        publishInventoryReserved(orderId);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("PAYMENT_PENDING"));

        publishPoisonPaymentAuthorized(orderId);

        await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("FAILED"));

        OrderDetail failed = getOrder(orderId);
        assertThat(failed.statusHistory()).last().satisfies(entry ->
                assertThat(entry.status()).isEqualTo("FAILED"));
        // Transition 9 is internal — no inbound event caused it — so its history row carries no
        // source_event_id, exactly like transitions 1/4/7 (docs/order-state-machine.md §3).
        assertThat(failed.statusHistory().getLast().sourceEventId()).isNull();
    }

    @Test
    void failedIsTerminalAndDoesNotRevertOnALaterDeadLetter() {
        OrderAccepted accepted = createOrder();
        String orderId = accepted.id();

        publishInventoryReserved(orderId);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("PAYMENT_PENDING"));

        publishPoisonPaymentAuthorized(orderId);
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(getOrder(orderId).status()).isEqualTo("FAILED"));
        int historySizeAtFailure = getOrder(orderId).statusHistory().size();

        // A second, independent poison record for the same order (e.g. a redelivered/duplicate DLQ
        // record) must not write a second history row or move the order anywhere — FAILED is
        // terminal, so OrderTransitions.classify() must return STALE for it.
        publishPoisonPaymentAuthorized(orderId);

        // No await-until-true assertion is possible for "nothing happens"; instead, wait long enough
        // for the DLQ round trip to have completed, then assert the state is unchanged.
        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            OrderDetail order = getOrder(orderId);
            assertThat(order.status()).isEqualTo("FAILED");
            assertThat(order.statusHistory()).hasSize(historySizeAtFailure);
        });
    }

    @Test
    void aDeadLetteredRecordForAnUnknownOrderIsIgnoredRatherThanThrowing() {
        String unknownOrderId = "order-does-not-exist-" + UUID.randomUUID();

        publishPoisonPaymentAuthorized(unknownOrderId);

        // Proof of "does not throw": the listener keeps consuming afterward. A real order published
        // right after the poison record for the unknown id must still reach FAILED normally — if the
        // dead-letter listener had died or the shared error handler had looped, this would time out.
        OrderAccepted accepted = createOrder();
        publishInventoryReserved(accepted.id());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(accepted.id()).status()).isEqualTo("PAYMENT_PENDING"));
        publishPoisonPaymentAuthorized(accepted.id());
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(getOrder(accepted.id()).status()).isEqualTo("FAILED"));
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

    private void publishInventoryReserved(String orderId) {
        InventoryReservedPayload payload = new InventoryReservedPayload(
                orderId, "resv-failed-1", List.of(new EventItem("SKU-001", 1)), Instant.now());
        com.orderfulfillment.common.CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                eventPublisher.publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, orderId, payload));
    }

    /**
     * A structurally valid envelope at the version the codec accepts, whose payload is not a
     * {@code PaymentAuthorizedPayload} — gets past {@code decode()} and fails in {@code payloadAs()},
     * a genuinely non-retryable failure ({@code JacksonException}) that dead-letters on the first
     * delivery (same technique as {@code OrderPoisonMessageIntegrationTest}). Published on
     * {@code payments.events}, which {@link OrderPaymentEventsConsumer} consumes.
     */
    private void publishPoisonPaymentAuthorized(String orderId) {
        String poison = """
                {"eventId":"%s","eventType":"%s","eventVersion":%d,"occurredAt":"%s",
                 "correlationId":"%s","aggregateId":"%s","payload":"this is not a PaymentAuthorized"}
                """.formatted(UUID.randomUUID(), EventTypes.PAYMENT_AUTHORIZED, EventTypes.CURRENT_VERSION,
                Instant.now(), UUID.randomUUID(), orderId);
        kafkaTemplate.send(KafkaTopics.PAYMENTS_EVENTS, orderId, poison);
    }
}

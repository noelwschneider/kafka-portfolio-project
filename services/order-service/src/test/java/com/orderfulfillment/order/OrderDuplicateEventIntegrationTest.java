package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
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
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Scenario 4 — Duplicate Event Delivery (docs/scenarios.md), Order Service's half.
 *
 * <p>Republishes one of Order Service's own consumed events — {@code InventoryReserved} off
 * {@code inventory.events} — a second time with the identical {@code eventId}, the identity a
 * redelivery reuses (docs/events/event-catalog.md §1). This is a real republish through Testcontainers
 * Kafka, consumed by the real {@link OrderInventoryEventsConsumer}: nothing here is simulated.
 *
 * <p>{@code InventoryReserved} drives two {@code order_status_history} rows in one local transaction
 * (transition 2, {@code INVENTORY_RESERVED}, plus internal transition 4, {@code PAYMENT_PENDING} —
 * docs/order-state-machine.md), so this is also the test that proves
 * {@link OrderPersistence#appendInventoryReservedTransition}'s claim covers both writes atomically:
 * a duplicate delivery must produce neither row a second time.
 */
class OrderDuplicateEventIntegrationTest extends AbstractIntegrationTest {

    @Test
    void republishingTheSameInventoryReservedEventAppliesTheTransitionOnce() {
        OrderAccepted accepted = createOrder("SKU-001", 1);
        UUID eventId = UUID.randomUUID();

        publishInventoryReserved(accepted.id(), eventId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(accepted.id()).status()).isEqualTo("PAYMENT_PENDING"));

        OrderDetail afterFirst = getOrder(accepted.id());
        assertThat(afterFirst.statusHistory()).extracting("status")
                .containsExactly("PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING");
        assertThat(ledgerRows(eventId)).isEqualTo(1);

        // Second, identical delivery — same eventId, same payload.
        publishInventoryReserved(accepted.id(), eventId);

        // Proving a non-event: hold the assertion true continuously rather than sampling it once,
        // so a second pair of history rows arriving late still fails the test.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OrderDetail detail = getOrder(accepted.id());
            assertThat(detail.status()).isEqualTo("PAYMENT_PENDING");
            assertThat(detail.statusHistory()).extracting("status")
                    .containsExactly("PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING");
        });

        // Consumed twice, applied once — and the ledger still holds exactly one row, because the
        // second delivery never got past the (event_id, consumer_name) claim.
        assertThat(ledgerRows(eventId)).isEqualTo(1);
    }

    @Test
    void theDuplicateDoesNotRepublishPaymentRequested() {
        OrderAccepted accepted = createOrder("SKU-001", 1);
        UUID eventId = UUID.randomUUID();

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.ORDERS_EVENTS);
        try {
            publishInventoryReserved(accepted.id(), eventId);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(getOrder(accepted.id()).status()).isEqualTo("PAYMENT_PENDING"));

            publishInventoryReserved(accepted.id(), eventId);
            await().atMost(Duration.ofSeconds(20)).until(() -> ledgerRows(eventId) == 1);

            // A duplicate must not produce a second PaymentRequested either: a downstream Payment
            // Service would otherwise see the same request announced twice, which is the downstream
            // half of the duplicate side effect Scenario 4 rules out.
            long paymentRequestedForThisOrder = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                for (var r : records) {
                    if (accepted.id().equals(r.key())
                            && r.value().contains("\"eventType\":\"" + EventTypes.PAYMENT_REQUESTED + "\"")) {
                        paymentRequestedForThisOrder++;
                    }
                }
            }
            assertThat(paymentRequestedForThisOrder).isEqualTo(1);
        } finally {
            consumer.close();
        }
    }

    private void publishInventoryReserved(String orderId, UUID eventId) {
        InventoryReservedPayload payload = new InventoryReservedPayload(
                orderId, "resv-dup-test", List.of(new EventItem("SKU-001", 1)), Instant.now());
        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                eventPublisher.publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED,
                        orderId, eventId, payload));
    }

    private long ledgerRows(UUID eventId) {
        Long count = jdbcClient.sql("SELECT count(*) FROM order_service.processed_events "
                        + "WHERE event_id = ? AND consumer_name = ?")
                .param(eventId).param(OrderConsumers.INVENTORY_EVENTS_CONSUMER)
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    private OrderAccepted createOrder(String sku, int quantity) {
        CreateOrderRequest request = new CreateOrderRequest("demo-customer",
                List.of(new CreateOrderItem(sku, quantity)));
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

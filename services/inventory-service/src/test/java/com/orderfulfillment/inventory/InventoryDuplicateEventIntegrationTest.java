package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.inventory.dto.InventoryItemDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Scenario 4 — Duplicate Event Delivery (docs/scenarios.md), Inventory Service's half.
 *
 * <p>The duplicate is a real republish, not a simulation: the identical envelope — same
 * {@code eventId}, same key, same payload bytes — is sent to {@code orders.events} a second time
 * through a real Testcontainers Kafka, and the real {@link InventoryOrderEventsConsumer} consumes
 * it a second time. That is what the scenario promises a reviewer ("the duplicate must be a real
 * republish rather than a UI label"), and it is the only version of this test that would catch the
 * idempotency check being wired in the wrong place.
 *
 * <p>The assertions are deliberately made against the database rather than against return values:
 * one {@code inventory_reservations} row, {@code reserved_quantity} advanced exactly once, and
 * exactly one {@code processed_events} row for {@code (eventId, "inventory.order-created")}.
 */
class InventoryDuplicateEventIntegrationTest extends AbstractIntegrationTest {

    private static final String SKU = "SKU-003"; // seeded at 100 — no contention with sibling tests

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    private String orderId;

    @AfterEach
    void restoreSeedState() {
        if (orderId != null) {
            jdbcClient.sql("DELETE FROM inventory_service.inventory_reservations WHERE order_id = ?")
                    .param(orderId).update();
        }
        jdbcClient.sql("UPDATE inventory_service.inventory_items SET reserved_quantity = 0 WHERE sku = ?")
                .param(SKU).update();
    }

    @Test
    void republishingTheSameOrderCreatedEventReservesOnlyOnce() {
        InventoryItemDto before = getInventory(SKU);
        orderId = "order-dup-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        // The exact bytes a real Order Service would have put on the topic. Built once and sent
        // twice, so the second delivery is byte-identical to the first — including its eventId,
        // which docs/events/event-catalog.md §1 defines as the identity a redelivery reuses.
        String record = objectMapper.writeValueAsString(new EventEnvelope<>(
                eventId, EventTypes.ORDER_CREATED, EventTypes.CURRENT_VERSION, Instant.now(),
                correlationId, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem(SKU, 2)))));

        kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, orderId, record);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getInventory(SKU).reservedQuantity()).isEqualTo(before.reservedQuantity() + 2));
        assertThat(ledgerRows(eventId)).isEqualTo(1);

        // Second, identical delivery.
        kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, orderId, record);

        // Proving a non-event: hold the assertion true continuously rather than sampling it once,
        // so a second reservation arriving late still fails the test.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(getInventory(SKU).reservedQuantity()).isEqualTo(before.reservedQuantity() + 2);
            assertThat(reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED))
                    .hasSize(1);
        });

        // Consumed twice, applied once — and the ledger still holds exactly one row, because the
        // second delivery never got past the (event_id, consumer_name) claim.
        assertThat(ledgerRows(eventId)).isEqualTo(1);
    }

    @Test
    void theDuplicateDoesNotRepublishTheOutcomeEvent() {
        InventoryItemDto before = getInventory(SKU);
        orderId = "order-dup-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        String record = objectMapper.writeValueAsString(new EventEnvelope<>(
                eventId, EventTypes.ORDER_CREATED, EventTypes.CURRENT_VERSION, Instant.now(),
                UUID.randomUUID(), orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem(SKU, 1)))));

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.INVENTORY_EVENTS);
        try {
            kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, orderId, record);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(getInventory(SKU).reservedQuantity()).isEqualTo(before.reservedQuantity() + 1));

            kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, orderId, record);
            await().atMost(Duration.ofSeconds(20)).until(() -> ledgerRows(eventId) == 1
                    && getInventory(SKU).reservedQuantity() == before.reservedQuantity() + 1);

            // A duplicate must not produce a second InventoryReserved either: Order Service would
            // otherwise see the same reservation announced twice, which is the downstream half of
            // the duplicate side effect Scenario 4 rules out.
            long reservedForThisOrder = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                for (var r : records) {
                    if (r.value().contains(orderId)
                            && r.value().contains("\"eventType\":\"" + EventTypes.INVENTORY_RESERVED + "\"")) {
                        reservedForThisOrder++;
                    }
                }
            }
            assertThat(reservedForThisOrder).isEqualTo(1);
        } finally {
            consumer.close();
        }
    }

    private long ledgerRows(UUID eventId) {
        Long count = jdbcClient.sql("SELECT count(*) FROM inventory_service.processed_events "
                        + "WHERE event_id = ? AND consumer_name = ?")
                .param(eventId).param("inventory.order-created")
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    private InventoryItemDto getInventory(String sku) {
        return client.get().uri("/api/inventory/" + sku).exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
    }
}

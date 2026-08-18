package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.inventory.dto.InventoryItemDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Inventory Service's own Kafka surface, proven in isolation per Phase 3's exit criteria — Order
 * Service is simulated by publishing OrderCreated/PaymentRejected directly (same wire format a
 * real Order Service would send). Covers {@link InventoryOrderEventsConsumer} and
 * {@link InventoryPaymentEventsConsumer}. Optimistic-locking retry logic itself is covered by the
 * ported unit tests ({@code InventoryReservationExecutorTest}, {@code InventoryServiceOptimisticLockTest})
 * — deeper concurrent-load verification (Scenario 7) is explicitly deferred to the follow-up
 * fan-out stage, not this boundary-definition step (see docs/agent-reports/phase-3-boundary.md).
 */
class InventoryServiceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void orderCreatedWithAdequateStockReservesAndPublishesInventoryReserved() {
        InventoryItemDto before = getInventory("SKU-001");
        String orderId = "order-test-" + UUID.randomUUID();

        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-001", 1))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            InventoryItemDto after = getInventory("SKU-001");
            assertThat(after.reservedQuantity()).isEqualTo(before.reservedQuantity() + 1);
        });

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.INVENTORY_EVENTS);
        try {
            await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record -> {
                    assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.INVENTORY_RESERVED + "\"");
                    assertThat(record.value()).contains(orderId);
                });
            });
        } finally {
            consumer.close();
        }
    }

    @Test
    void orderCreatedExceedingStockPublishesInventoryReservationFailed() {
        String orderId = "order-test-" + UUID.randomUUID();
        // SKU-004 seeds at 2 units available; request more than that.
        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-004", 10))));

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.INVENTORY_EVENTS);
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record -> {
                    assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.INVENTORY_RESERVATION_FAILED + "\"");
                    assertThat(record.value()).contains(orderId);
                });
            });
        } finally {
            consumer.close();
        }
    }

    @Test
    void paymentRejectedReleasesReservationAndPublishesInventoryReleased() {
        InventoryItemDto before = getInventory("SKU-003");
        String orderId = "order-test-" + UUID.randomUUID();

        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-003", 1))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            InventoryItemDto reserved = getInventory("SKU-003");
            assertThat(reserved.reservedQuantity()).isEqualTo(before.reservedQuantity() + 1);
        });

        publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_REJECTED, orderId,
                new PaymentRejectedPayload(orderId, "pay-test-1", new BigDecimal("14.50"),
                        "CARD_DECLINED", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            InventoryItemDto released = getInventory("SKU-003");
            assertThat(released.reservedQuantity()).isEqualTo(before.reservedQuantity());
        });

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.INVENTORY_EVENTS);
        try {
            await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record ->
                        assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.INVENTORY_RELEASED + "\""));
            });
        } finally {
            consumer.close();
        }
    }

    private InventoryItemDto getInventory(String sku) {
        return client.get().uri("/api/inventory/" + sku).exchange()
                .expectBody(InventoryItemDto.class).returnResult().getResponseBody();
    }

    private void publish(String topic, String eventType, String aggregateId, Object payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(),
                () -> eventPublisher.publish(topic, eventType, aggregateId, payload));
    }
}

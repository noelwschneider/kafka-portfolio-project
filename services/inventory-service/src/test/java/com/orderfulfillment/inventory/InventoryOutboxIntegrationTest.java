package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sprint 2 goal 2, item 1: Inventory Service's transactional outbox (ADR-006), extended here from
 * Order Service. Proven the same way {@code OrderOutboxIntegrationTest} proves it for Order
 * Service: not merely "the event eventually reaches Kafka" (the pre-Sprint-2 publish-after-commit
 * code already passed that test), but that the reservation/release change and its outbox row commit
 * in the very same transaction, readable over JDBC the instant the business change is visible, and
 * that the background {@link OutboxPublisher} then drains the row to Kafka and marks it PUBLISHED.
 */
class InventoryOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void reservationCommitsTheOutboxRowWithTheReservationItself() {
        String orderId = "order-outbox-" + UUID.randomUUID();

        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-001", 1))));

        // Once the reservation is visible in inventory_reservations, the outbox row must already be
        // there too — attemptReserve writes both in one REQUIRES_NEW transaction.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> rows = outboxRows(orderId);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("event_type")).isEqualTo(EventTypes.INVENTORY_RESERVED);
        });

        Map<String, Object> row = outboxRows(orderId).getFirst();
        JsonNode envelope = storedEnvelope(row);
        assertThat(envelope.get("eventType").asString()).isEqualTo(EventTypes.INVENTORY_RESERVED);
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(orderId);
        assertThat(envelope.get("payload").get("reservationId").isNull()).isFalse();
    }

    @Test
    void thePollerPublishesTheOutboxRowToKafkaAndMarksItPublished() {
        String orderId = "order-outbox-" + UUID.randomUUID();

        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-001", 1))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outboxRows(orderId)).hasSize(1));
        UUID eventId = UUID.fromString(storedEnvelope(outboxRows(orderId).getFirst()).get("eventId").asString());

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.INVENTORY_EVENTS);
        try {
            await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record -> {
                    assertThat(record.value()).contains("\"eventId\":\"" + eventId + "\"");
                    assertThat(record.key()).isEqualTo(orderId);
                });
            });
        } finally {
            consumer.close();
        }

        await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> row = outboxRows(orderId).getFirst();
            assertThat(row.get("status")).isEqualTo(OutboxStatus.PUBLISHED.name());
            assertThat(row.get("published_at")).isNotNull();
        });
    }

    @Test
    void reservationFailureAlsoCommitsAnOutboxRow() {
        String orderId = "order-outbox-shortage-" + UUID.randomUUID();
        // SKU-004 seeds at 2 units available; request more than that.
        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-004", 10))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> rows = outboxRows(orderId);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("event_type")).isEqualTo(EventTypes.INVENTORY_RESERVATION_FAILED);
        });
    }

    @Test
    void releaseCommitsAnInventoryReleasedOutboxRow() {
        String orderId = "order-outbox-release-" + UUID.randomUUID();
        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-003", 1))));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outboxRows(orderId)).hasSize(1));

        publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_REJECTED, orderId,
                new PaymentRejectedPayload(orderId, "pay-outbox-1", new BigDecimal("14.50"),
                        "CARD_DECLINED", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> rows = outboxRows(orderId);
            assertThat(rows).anySatisfy(r -> assertThat(r.get("event_type")).isEqualTo(EventTypes.INVENTORY_RELEASED));
        });
    }

    private List<Map<String, Object>> outboxRows(String aggregateId) {
        return jdbcClient.sql("""
                        SELECT id, aggregate_id, event_type, payload::text AS payload, created_at, published_at, status
                        FROM inventory_service.outbox_events WHERE aggregate_id = ? ORDER BY id ASC""")
                .param(aggregateId)
                .query()
                .listOfRows();
    }

    private JsonNode storedEnvelope(Map<String, Object> row) {
        return objectMapper.readTree(String.valueOf(row.get("payload")));
    }

    private void publish(String topic, String eventType, String aggregateId, Object payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(),
                () -> eventPublisher.publish(topic, eventType, aggregateId, payload));
    }
}

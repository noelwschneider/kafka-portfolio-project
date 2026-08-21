package com.orderfulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sprint 2 goal 2, item 1: Fulfillment Service's transactional outbox (ADR-006), extended here from
 * Order Service — see {@code InventoryOutboxIntegrationTest} in inventory-service for the same
 * pattern applied there. Proves the {@code shipments} row and its ShipmentCreated outbox row commit
 * in the same transaction, and that {@link OutboxPublisher} drains it to Kafka afterward.
 */
class FulfillmentOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void shipmentCreationCommitsTheOutboxRowWithTheShipmentItself() {
        String orderId = "order-outbox-" + UUID.randomUUID();

        publish(orderId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> rows = outboxRows(orderId);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("event_type")).isEqualTo(EventTypes.SHIPMENT_CREATED);
        });

        JsonNode envelope = storedEnvelope(outboxRows(orderId).getFirst());
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(orderId);
        assertThat(envelope.get("payload").get("shipmentId").isNull()).isFalse();
        assertThat(envelope.get("payload").get("trackingNumber").isNull()).isFalse();
    }

    @Test
    void thePollerPublishesTheShipmentCreatedRowAndMarksItPublished() {
        String orderId = "order-outbox-" + UUID.randomUUID();
        publish(orderId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outboxRows(orderId)).hasSize(1));
        UUID eventId = UUID.fromString(storedEnvelope(outboxRows(orderId).getFirst()).get("eventId").asString());

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.FULFILLMENT_EVENTS);
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

    private List<Map<String, Object>> outboxRows(String aggregateId) {
        return jdbcClient.sql("""
                        SELECT id, aggregate_id, event_type, payload::text AS payload, created_at, published_at, status
                        FROM fulfillment_service.outbox_events WHERE aggregate_id = ? ORDER BY id ASC""")
                .param(aggregateId)
                .query()
                .listOfRows();
    }

    private JsonNode storedEnvelope(Map<String, Object> row) {
        return objectMapper.readTree(String.valueOf(row.get("payload")));
    }

    private void publish(String orderId) {
        PaymentAuthorizedPayload payload =
                new PaymentAuthorizedPayload(orderId, "pay-outbox-1", new BigDecimal("50.00"), Instant.now());
        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                eventPublisher.publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_AUTHORIZED, orderId, payload));
    }
}

package com.orderfulfillment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.PaymentRequestedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.payment.dto.PaymentBehaviorDto;
import com.orderfulfillment.payment.dto.PaymentBehaviorMode;
import java.math.BigDecimal;
import java.time.Duration;
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
 * Sprint 2 goal 2, item 1: Payment Service's transactional outbox (ADR-006), extended here from
 * Order Service — see {@code InventoryOutboxIntegrationTest} in inventory-service for the same
 * pattern applied there. Proves the {@code payment_attempts} row and its outbox row commit in the
 * same transaction, and that {@link OutboxPublisher} drains it to Kafka afterward.
 */
class PaymentOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void authorizationCommitsThePaymentAuthorizedOutboxRowWithTheAttemptItself() {
        String orderId = "order-outbox-" + UUID.randomUUID();

        publish(new PaymentRequestedPayload(orderId, new BigDecimal("129.00"), UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> rows = outboxRows(orderId);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("event_type")).isEqualTo(EventTypes.PAYMENT_AUTHORIZED);
        });

        JsonNode envelope = storedEnvelope(outboxRows(orderId).getFirst());
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(orderId);
        assertThat(new BigDecimal(envelope.get("payload").get("amount").asString()))
                .isEqualByComparingTo("129.00");
    }

    @Test
    void thePollerPublishesThePaymentAuthorizedRowAndMarksItPublished() {
        String orderId = "order-outbox-" + UUID.randomUUID();
        publish(new PaymentRequestedPayload(orderId, new BigDecimal("50.00"), UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outboxRows(orderId)).hasSize(1));
        UUID eventId = UUID.fromString(storedEnvelope(outboxRows(orderId).getFirst()).get("eventId").asString());

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.PAYMENTS_EVENTS);
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
    void rejectionCommitsAPaymentRejectedOutboxRow() {
        paymentBehaviorStore.set(new PaymentBehaviorDto(PaymentBehaviorMode.REJECT, null, "CARD_DECLINED"));
        String orderId = "order-outbox-reject-" + UUID.randomUUID();

        publish(new PaymentRequestedPayload(orderId, new BigDecimal("75.00"), UUID.randomUUID()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> rows = outboxRows(orderId);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().get("event_type")).isEqualTo(EventTypes.PAYMENT_REJECTED);
        });
    }

    private List<Map<String, Object>> outboxRows(String aggregateId) {
        return jdbcClient.sql("""
                        SELECT id, aggregate_id, event_type, payload::text AS payload, created_at, published_at, status
                        FROM payment_service.outbox_events WHERE aggregate_id = ? ORDER BY id ASC""")
                .param(aggregateId)
                .query()
                .listOfRows();
    }

    private JsonNode storedEnvelope(Map<String, Object> row) {
        return objectMapper.readTree(String.valueOf(row.get("payload")));
    }

    private void publish(PaymentRequestedPayload payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                eventPublisher.publish(KafkaTopics.ORDERS_EVENTS, EventTypes.PAYMENT_REQUESTED, payload.orderId(), payload));
    }
}

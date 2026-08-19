package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.InventoryReservedPayload;
import com.orderfulfillment.common.events.OrderCreatedPayload;
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
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 6's transactional outbox (ADR-006), proven at the level that actually matters: not "an
 * event eventually shows up on Kafka" — the old publish-after-commit code passed that test too —
 * but that the business row and the row describing its event are committed by the same
 * transaction, and that the background publisher then drains them.
 *
 * <p>The atomicity assertions all read {@code outbox_events} directly over JDBC immediately after
 * the business change becomes visible, with no Kafka involved: if the outbox row were written
 * anywhere but inside that transaction, there would be an instant where the order is visible and
 * the row is not, and these reads would catch it.
 */
class OrderOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OutboxRecorder outboxRecorder;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void createOrderCommitsTheOutboxRowWithTheOrderItself() {
        OrderAccepted accepted = createOrder("SKU-001", 1);

        // The HTTP response returned only after createPendingOrder's transaction committed, so if
        // the outbox insert is inside that transaction the row is already here — no waiting.
        List<Map<String, Object>> rows = outboxRows(accepted.id());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("event_type")).isEqualTo(EventTypes.ORDER_CREATED);
        assertThat(String.valueOf(row.get("status"))).isIn(OutboxStatus.PENDING.name(), OutboxStatus.PUBLISHED.name());

        // The stored payload is the whole frozen envelope (event-catalog.md §1), not just the
        // business fields — that is what lets the dispatcher publish without rebuilding anything.
        JsonNode envelope = storedEnvelope(row);
        assertThat(envelope.get("eventType").asString()).isEqualTo(EventTypes.ORDER_CREATED);
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(accepted.id());
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(EventTypes.CURRENT_VERSION);
        assertThat(envelope.get("correlationId").isNull()).isFalse();
        assertThat(envelope.get("occurredAt").isNull()).isFalse();
        assertThat(envelope.get("payload").get("orderId").asString()).isEqualTo(accepted.id());
    }

    @Test
    void thePollerPublishesTheOutboxRowToKafkaAndMarksItPublished() {
        OrderAccepted accepted = createOrder("SKU-001", 1);
        UUID eventId = UUID.fromString(storedEnvelope(outboxRows(accepted.id()).getFirst()).get("eventId").asString());

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.ORDERS_EVENTS);
        try {
            await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record -> {
                    // Same eventId the transaction committed: the dispatcher sends the stored bytes,
                    // it does not rebuild the envelope at send time.
                    assertThat(record.value()).contains("\"eventId\":\"" + eventId + "\"");
                    assertThat(record.key()).isEqualTo(accepted.id());
                });
            });
        } finally {
            consumer.close();
        }

        await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> row = outboxRows(accepted.id()).getFirst();
            assertThat(row.get("status")).isEqualTo(OutboxStatus.PUBLISHED.name());
            assertThat(row.get("published_at")).isNotNull();
        });
    }

    /**
     * The publish site ADR-006's prose did not cover. {@code appendInventoryReservedTransition}
     * commits the {@code processed_events} claim together with the status writes, so a crash before
     * a post-commit publish would have stranded the order at PAYMENT_PENDING — a redelivered
     * InventoryReserved is discarded as a duplicate and never retries the publish. Recording
     * PaymentRequested in the same transaction is what removes that window, and what this asserts:
     * the moment PAYMENT_PENDING is visible, the event is already durable.
     */
    @Test
    void inventoryReservedCommitsPaymentRequestedWithTheStatusTransition() {
        OrderAccepted accepted = createOrder("SKU-001", 1);

        publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, accepted.id(),
                new InventoryReservedPayload(accepted.id(), "resv-outbox-1",
                        List.of(new EventItem("SKU-001", 1)), Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(getOrder(accepted.id()).status()).isEqualTo("PAYMENT_PENDING"));

        Optional<Map<String, Object>> paymentRequested = outboxRows(accepted.id()).stream()
                .filter(r -> EventTypes.PAYMENT_REQUESTED.equals(r.get("event_type")))
                .findFirst();
        assertThat(paymentRequested).as("PaymentRequested outbox row, committed with the transition").isPresent();

        JsonNode envelope = storedEnvelope(paymentRequested.orElseThrow());
        // PaymentRequested's idempotencyKey is its own eventId (event-catalog.md §3) — recording the
        // envelope at transaction time is what lets the payload reference an id that is already final.
        assertThat(envelope.get("payload").get("idempotencyKey").asString())
                .isEqualTo(envelope.get("eventId").asString());

        await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> current = outboxRows(accepted.id()).stream()
                    .filter(r -> EventTypes.PAYMENT_REQUESTED.equals(r.get("event_type")))
                    .findFirst().orElseThrow();
            assertThat(current.get("status")).isEqualTo(OutboxStatus.PUBLISHED.name());
        });
    }

    @Test
    void anOutboxRowRollsBackWithItsTransaction() {
        String aggregateId = "ORD-ROLLBACK-" + UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                assertThatThrownBy(() -> template.executeWithoutResult(status -> {
                    outboxRecorder.record(EventTypes.ORDER_CREATED, aggregateId,
                            new OrderCreatedPayload(aggregateId, "demo-customer", List.of(new EventItem("SKU-001", 1))));
                    throw new IllegalStateException("business failure after the outbox insert");
                })).isInstanceOf(IllegalStateException.class));

        assertThat(outboxRows(aggregateId))
                .as("a rolled-back transaction must leave no event behind to publish")
                .isEmpty();
    }

    /**
     * The mechanical guard: recording an event outside a transaction would recreate the very
     * dual-write window the outbox exists to close, so it is a startup-visible bug, not a silent
     * degradation.
     */
    @Test
    void recordingAnEventOutsideATransactionIsRejected() {
        String aggregateId = "ORD-NOTX-" + UUID.randomUUID();

        CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                assertThatThrownBy(() -> outboxRecorder.record(EventTypes.ORDER_CREATED, aggregateId,
                        new OrderCreatedPayload(aggregateId, "demo-customer", List.of(new EventItem("SKU-001", 1)))))
                        .isInstanceOf(IllegalTransactionStateException.class));

        assertThat(outboxRows(aggregateId)).isEmpty();
    }

    private List<Map<String, Object>> outboxRows(String aggregateId) {
        return jdbcClient.sql("""
                        SELECT id, aggregate_id, event_type, payload::text AS payload, created_at, published_at, status
                        FROM order_service.outbox_events WHERE aggregate_id = ? ORDER BY id ASC""")
                .param(aggregateId)
                .query()
                .listOfRows();
    }

    /**
     * Parses rather than string-matches the stored payload on purpose: {@code jsonb} normalizes the
     * text it was given (key order, whitespace), so only the parsed document is meaningful — see
     * {@code OutboxDispatcher#wireForm}.
     */
    private JsonNode storedEnvelope(Map<String, Object> row) {
        return objectMapper.readTree(String.valueOf(row.get("payload")));
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

    private void publish(String topic, String eventType, String aggregateId, Object payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(),
                () -> eventPublisher.publish(topic, eventType, aggregateId, payload));
    }
}

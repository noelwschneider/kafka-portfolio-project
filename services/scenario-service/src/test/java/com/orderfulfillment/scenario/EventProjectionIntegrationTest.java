package com.orderfulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the event projection this Phase 5 addition resolves docs/db-ownership.md §4's Event
 * Explorer gap with: a real record on {@code orders.events} is consumed by
 * {@code EventProjectionConsumer} and shows up through {@code GET /demo/events}, and a record on a
 * {@code .dlq} topic is projected with {@code deadLettered=true}.
 */
class EventProjectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void aDomainRecordIsProjectedAndQueryable() {
        String orderId = "order-proj-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EventEnvelope<OrderCreatedPayload> envelope = new EventEnvelope<>(
                eventId, EventTypes.ORDER_CREATED, EventTypes.CURRENT_VERSION, Instant.now(), UUID.randomUUID(),
                orderId, new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-001", 1))));
        kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, orderId, objectMapper.writeValueAsString(envelope));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> page = client.get().uri("/demo/events?aggregateId=" + orderId)
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            @SuppressWarnings("unchecked")
            var content = (List<Map<String, Object>>) page.get("content");
            assertThat(content).hasSize(1);
            Map<String, Object> event = content.get(0);
            assertThat(event.get("eventId")).isEqualTo(eventId.toString());
            assertThat(event.get("topic")).isEqualTo(KafkaTopics.ORDERS_EVENTS);
            assertThat(event.get("producer")).isEqualTo("order-service");
            assertThat(event.get("deadLettered")).isEqualTo(false);
            assertThat(event.get("partition")).isNotNull();
            assertThat(event.get("offset")).isNotNull();
        });
    }

    @Test
    void aDlqRecordIsProjectedAsDeadLettered() {
        String orderId = "order-dlq-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        EventEnvelope<OrderCreatedPayload> envelope = new EventEnvelope<>(
                eventId, EventTypes.ORDER_CREATED, EventTypes.CURRENT_VERSION, Instant.now(), UUID.randomUUID(),
                orderId, new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-001", 1))));
        kafkaTemplate.send(KafkaTopics.INVENTORY_DLQ, orderId, objectMapper.writeValueAsString(envelope));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> page = client.get().uri("/demo/events?aggregateId=" + orderId + "&deadLettered=true")
                    .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
            @SuppressWarnings("unchecked")
            var content = (List<Map<String, Object>>) page.get("content");
            assertThat(content).hasSize(1);
            assertThat(content.get(0).get("deadLettered")).isEqualTo(true);
            assertThat(content.get(0).get("producer")).isEqualTo("inventory-service");
        });
    }
}

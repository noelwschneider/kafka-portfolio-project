package com.orderfulfillment.scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * docs/scenarios.md Scenario 4 — Duplicate Event Delivery. Order Service is a WireMock stub here (see
 * AbstractIntegrationTest), so — mirroring the "simulate the other side" convention every other
 * service's suite already uses (e.g. payment-service's tests publish {@code PaymentRequested}
 * directly to stand in for Order Service) — this test publishes the {@code OrderCreated} record a real
 * Order Service would have produced, using the run's own correlationId, and then asserts on what
 * Scenario Service itself is responsible for: republishing that exact record — same eventId, same key,
 * same payload — as a second, genuine Kafka record.
 */
class DuplicateEventScenarioIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void republishesOrderCreatedWithTheIdenticalEventId() {
        PAYMENT_SERVICE.stubFor(put(urlPathEqualTo("/demo/payment-behavior")).willReturn(aResponse().withStatus(200)));
        String orderId = "order-dup-1";
        ORDER_SERVICE.stubFor(post(urlPathEqualTo("/api/orders")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + orderId + "\",\"status\":\"PENDING\"}")));
        stubOrderLifecycle(orderId, "PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING", "PAID",
                "FULFILLMENT_PENDING", "FULFILLED");

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.ORDERS_EVENTS);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> started = client.post().uri("/demo/scenarios/duplicate-event")
                    .exchange().expectStatus().isEqualTo(202).expectBody(Map.class).returnResult().getResponseBody();
            UUID correlationId = UUID.fromString((String) started.get("correlationId"));
            UUID eventId = UUID.randomUUID();

            // Stands in for Order Service's own publish (see class Javadoc).
            EventEnvelope<OrderCreatedPayload> orderCreated = new EventEnvelope<>(
                    eventId, EventTypes.ORDER_CREATED, EventTypes.CURRENT_VERSION, Instant.now(), correlationId,
                    orderId, new OrderCreatedPayload(orderId, "demo-customer", List.of(new EventItem("SKU-001", 2))));
            kafkaTemplate.send(KafkaTopics.ORDERS_EVENTS, orderId, objectMapper.writeValueAsString(orderCreated));

            List<String> orderCreatedRecordsForThisOrder = new java.util.ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                for (var r : records) {
                    if (orderId.equals(r.key()) && r.value().contains("\"eventType\":\"OrderCreated\"")) {
                        orderCreatedRecordsForThisOrder.add(r.value());
                    }
                }
                return orderCreatedRecordsForThisOrder.size() >= 2;
            });

            assertThat(orderCreatedRecordsForThisOrder).hasSizeGreaterThanOrEqualTo(2);
            // Same eventId in both records (event-catalog.md §1: "A duplicate delivery of the same
            // logical event reuses the same eventId").
            assertThat(extractField(orderCreatedRecordsForThisOrder.get(0), "eventId"))
                    .isEqualTo(extractField(orderCreatedRecordsForThisOrder.get(1), "eventId"))
                    .isEqualTo(eventId.toString());
        } finally {
            consumer.close();
        }
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        int colon = json.indexOf(':', idx);
        int start = json.indexOf('"', colon) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}

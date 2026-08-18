package com.orderfulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Scenario 4 — Duplicate Event Delivery (docs/scenarios.md), Fulfillment Service's half.
 *
 * <p>The duplicate is a real republish, not a simulation: the identical envelope — same
 * {@code eventId}, same key, same payload bytes — is sent to {@code payments.events} a second time
 * through a real Testcontainers Kafka, and the real {@link FulfillmentPaymentEventsConsumer}
 * consumes it a second time. Assertions are made against the database rather than against return
 * values: exactly one {@code shipments} row for the order, and exactly one {@code processed_events}
 * row for {@code (eventId, "fulfillment.payment-authorized")}.
 */
class FulfillmentDuplicateEventIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void republishingTheSamePaymentAuthorizedEventCreatesOnlyOneShipment() {
        String orderId = "order-dup-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        // The exact bytes a real Payment Service would have put on the topic. Built once and sent
        // twice, so the second delivery is byte-identical to the first — including its eventId,
        // which docs/events/event-catalog.md §1 defines as the identity a redelivery reuses.
        String record = objectMapper.writeValueAsString(new EventEnvelope<>(
                eventId, EventTypes.PAYMENT_AUTHORIZED, EventTypes.CURRENT_VERSION, Instant.now(),
                correlationId, orderId,
                new PaymentAuthorizedPayload(orderId, "pay-dup-1", new BigDecimal("50.00"), Instant.now())));

        kafkaTemplate.send(KafkaTopics.PAYMENTS_EVENTS, orderId, record);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(shipmentRowCount(orderId)).isEqualTo(1));
        assertThat(ledgerRows(eventId)).isEqualTo(1);

        // Second, identical delivery.
        kafkaTemplate.send(KafkaTopics.PAYMENTS_EVENTS, orderId, record);

        // Proving a non-event: hold the assertion true continuously rather than sampling it once,
        // so a second shipment arriving late still fails the test.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(shipmentRowCount(orderId)).isEqualTo(1));

        // Consumed twice, applied once — and the ledger still holds exactly one row, because the
        // second delivery never got past the (event_id, consumer_name) claim.
        assertThat(ledgerRows(eventId)).isEqualTo(1);
    }

    @Test
    void theDuplicateDoesNotRepublishTheOutcomeEvent() {
        String orderId = "order-dup-" + UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        String record = objectMapper.writeValueAsString(new EventEnvelope<>(
                eventId, EventTypes.PAYMENT_AUTHORIZED, EventTypes.CURRENT_VERSION, Instant.now(),
                UUID.randomUUID(), orderId,
                new PaymentAuthorizedPayload(orderId, "pay-dup-2", new BigDecimal("75.00"), Instant.now())));

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.FULFILLMENT_EVENTS);
        try {
            kafkaTemplate.send(KafkaTopics.PAYMENTS_EVENTS, orderId, record);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(shipmentRowCount(orderId)).isEqualTo(1));

            kafkaTemplate.send(KafkaTopics.PAYMENTS_EVENTS, orderId, record);
            await().atMost(Duration.ofSeconds(20)).until(() -> ledgerRows(eventId) == 1);

            // A duplicate must not produce a second ShipmentCreated either: Order Service would
            // otherwise see the same shipment announced twice, which is the downstream half of the
            // duplicate side effect Scenario 4 rules out.
            long createdForThisOrder = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                for (var r : records) {
                    if (r.value().contains(orderId)
                            && r.value().contains("\"eventType\":\"" + EventTypes.SHIPMENT_CREATED + "\"")) {
                        createdForThisOrder++;
                    }
                }
            }
            assertThat(createdForThisOrder).isEqualTo(1);
        } finally {
            consumer.close();
        }
    }

    private long shipmentRowCount(String orderId) {
        Long count = jdbcClient.sql("SELECT count(*) FROM fulfillment_service.shipments WHERE order_id = ?")
                .param(orderId).query(Long.class).single();
        return count == null ? 0 : count;
    }

    private long ledgerRows(UUID eventId) {
        Long count = jdbcClient.sql("SELECT count(*) FROM fulfillment_service.processed_events "
                        + "WHERE event_id = ? AND consumer_name = ?")
                .param(eventId).param("fulfillment.payment-authorized")
                .query(Long.class).single();
        return count == null ? 0 : count;
    }
}

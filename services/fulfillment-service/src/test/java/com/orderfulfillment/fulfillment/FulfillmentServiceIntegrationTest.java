package com.orderfulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.fulfillment.dto.ShipmentDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Fulfillment Service's own Kafka surface, proven in isolation per Phase 3's exit criteria —
 * Payment Service is simulated by publishing PaymentAuthorized directly. Covers
 * {@link FulfillmentPaymentEventsConsumer}.
 */
class FulfillmentServiceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void paymentAuthorizedCreatesShipmentAndPublishesShipmentCreated() {
        String orderId = "order-test-" + UUID.randomUUID();

        publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_AUTHORIZED, orderId,
                new PaymentAuthorizedPayload(orderId, "pay-test-1", new BigDecimal("129.00"), Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ShipmentDto shipment = client.get().uri("/api/shipments/" + orderId).exchange()
                    .expectBody(ShipmentDto.class).returnResult().getResponseBody();
            assertThat(shipment.status()).isEqualTo("CREATED");
        });

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.FULFILLMENT_EVENTS);
        try {
            await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record -> {
                    assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.SHIPMENT_CREATED + "\"");
                    assertThat(record.value()).contains(orderId);
                });
            });
        } finally {
            consumer.close();
        }
    }

    private void publish(String topic, String eventType, String aggregateId, Object payload) {
        CorrelationIdHolder.runInScope(UUID.randomUUID(),
                () -> eventPublisher.publish(topic, eventType, aggregateId, payload));
    }
}

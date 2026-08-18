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
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Payment Service's own Kafka surface, proven in isolation per Phase 3's exit criteria — Order
 * Service is simulated by publishing PaymentRequested directly. Covers
 * {@link PaymentOrderEventsConsumer} and the {@code /demo/payment-behavior} simulator control
 * ({@link PaymentDemoController}) that drives it. Deterministic default-success/reject-mode logic
 * itself is covered by the ported unit test ({@code PaymentServiceTest}).
 */
class PaymentServiceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void paymentRequestedDefaultsToAuthorized() {
        String orderId = "order-test-" + UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.PAYMENT_REQUESTED, orderId,
                new PaymentRequestedPayload(orderId, new BigDecimal("129.00"), idempotencyKey));

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.PAYMENTS_EVENTS);
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record -> {
                    assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.PAYMENT_AUTHORIZED + "\"");
                    assertThat(record.value()).contains(orderId);
                });
            });
        } finally {
            consumer.close();
        }
    }

    @Test
    void paymentRequestedWithRejectModeArmedPublishesPaymentRejected() {
        client.put().uri("/demo/payment-behavior")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PaymentBehaviorDto(PaymentBehaviorMode.REJECT, null, "CARD_DECLINED"))
                .exchange()
                .expectStatus().isOk();

        String orderId = "order-test-" + UUID.randomUUID();
        publish(KafkaTopics.ORDERS_EVENTS, EventTypes.PAYMENT_REQUESTED, orderId,
                new PaymentRequestedPayload(orderId, new BigDecimal("14.50"), UUID.randomUUID()));

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.PAYMENTS_EVENTS);
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record -> {
                    assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.PAYMENT_REJECTED + "\"");
                    assertThat(record.value()).contains(orderId);
                    assertThat(record.value()).contains("CARD_DECLINED");
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

package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.InventoryReservationFailedPayload;
import com.orderfulfillment.common.events.InventoryReservedPayload;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
import com.orderfulfillment.common.events.ShipmentCreatedPayload;
import com.orderfulfillment.common.events.ShortageItem;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import com.orderfulfillment.order.dto.OrderDetail;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Order Service's own Kafka surface, proven in isolation per Phase 3's exit criteria — Inventory/
 * Payment/Fulfillment Service reactions are simulated by publishing the exact events they would
 * have produced (using the same {@link com.orderfulfillment.common.kafka.EventPublisher} bean this
 * service itself uses, so the wire format is identical), rather than standing up the other three
 * services. Covers: {@code OrderService.createOrder} (OrderCreated producer),
 * {@link OrderInventoryEventsConsumer}, {@link OrderPaymentEventsConsumer},
 * {@link OrderFulfillmentEventsConsumer}.
 */
class OrderServiceIntegrationTest extends AbstractIntegrationTest {

    @Test
    void createOrderPersistsPendingAndPublishesOrderCreated() {
        OrderAccepted accepted = createOrder("SKU-001", 1);

        assertThat(accepted.status()).isEqualTo("PENDING");

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.ORDERS_EVENTS);
        try {
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, POLL_TIMEOUT);
            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            assertThat(records).anySatisfy(record -> {
                assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.ORDER_CREATED + "\"");
                assertThat(record.value()).contains(accepted.id());
            });
        } finally {
            consumer.close();
        }
    }

    @Test
    void inventoryReservedDrivesOrderToPaymentPendingAndPublishesPaymentRequested() {
        OrderAccepted accepted = createOrder("SKU-001", 1);

        publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, accepted.id(),
                new InventoryReservedPayload(accepted.id(), "resv-test-1",
                        List.of(new EventItem("SKU-001", 1)), Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OrderDetail detail = getOrder(accepted.id());
            assertThat(detail.status()).isEqualTo("PAYMENT_PENDING");
        });

        OrderDetail detail = getOrder(accepted.id());
        assertThat(detail.statusHistory()).extracting("status")
                .containsExactly("PENDING", "INVENTORY_RESERVED", "PAYMENT_PENDING");

        Consumer<String, String> consumer = rawConsumer(KafkaTopics.ORDERS_EVENTS);
        try {
            await().atMost(POLL_TIMEOUT).untilAsserted(() -> {
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2));
                assertThat(records).anySatisfy(record ->
                        assertThat(record.value()).contains("\"eventType\":\"" + EventTypes.PAYMENT_REQUESTED + "\""));
            });
        } finally {
            consumer.close();
        }
    }

    @Test
    void inventoryReservationFailedIsTerminal() {
        OrderAccepted accepted = createOrder("SKU-004", 10);

        publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVATION_FAILED, accepted.id(),
                new InventoryReservationFailedPayload(accepted.id(), "OUT_OF_STOCK",
                        List.of(new ShortageItem("SKU-004", 10, 2))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OrderDetail detail = getOrder(accepted.id());
            assertThat(detail.status()).isEqualTo("REJECTED_OUT_OF_STOCK");
        });
        OrderDetail detail = getOrder(accepted.id());
        assertThat(detail.statusHistory()).extracting("status")
                .containsExactly("PENDING", "REJECTED_OUT_OF_STOCK");
    }

    @Test
    void paymentAuthorizedThenShipmentCreatedReachesFulfilled() {
        OrderAccepted accepted = createOrder("SKU-002", 1);

        publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_AUTHORIZED, accepted.id(),
                new PaymentAuthorizedPayload(accepted.id(), "pay-test-1", new BigDecimal("189.00"), Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OrderDetail detail = getOrder(accepted.id());
            assertThat(detail.status()).isEqualTo("FULFILLMENT_PENDING");
        });

        publish(KafkaTopics.FULFILLMENT_EVENTS, EventTypes.SHIPMENT_CREATED, accepted.id(),
                new ShipmentCreatedPayload(accepted.id(), "shp-test-1", "TRACK-1", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OrderDetail detail = getOrder(accepted.id());
            assertThat(detail.status()).isEqualTo("FULFILLED");
        });

        OrderDetail detail = getOrder(accepted.id());
        assertThat(detail.statusHistory()).extracting("status")
                .containsExactly("PENDING", "PAID", "FULFILLMENT_PENDING", "FULFILLED");
    }

    @Test
    void paymentRejectedIsTerminal() {
        OrderAccepted accepted = createOrder("SKU-002", 1);

        publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_REJECTED, accepted.id(),
                new PaymentRejectedPayload(accepted.id(), "pay-test-2", new BigDecimal("189.00"),
                        "CARD_DECLINED", Instant.now()));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            OrderDetail detail = getOrder(accepted.id());
            assertThat(detail.status()).isEqualTo("PAYMENT_FAILED");
        });
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

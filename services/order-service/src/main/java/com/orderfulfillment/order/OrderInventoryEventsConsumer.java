package com.orderfulfillment.order;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.InventoryReservationFailedPayload;
import com.orderfulfillment.common.events.InventoryReservedPayload;
import com.orderfulfillment.common.events.PaymentRequestedPayload;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventPublisher;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Order Service's consumer for {@code inventory.events}, driving transitions 2/3 of
 * docs/order-state-machine.md and, on success, transition 4 (publishing PaymentRequested).
 * Own consumer group ("order-service") so Order Service reads every record on this topic
 * independently of any other consumer group.
 */
@Component
public class OrderInventoryEventsConsumer {

    private static final String GROUP_ID = "order-service";

    private final OrderPersistence persistence;
    private final OrderRepository orderRepository;
    private final EventCodec eventCodec;
    private final EventPublisher eventPublisher;

    public OrderInventoryEventsConsumer(OrderPersistence persistence, OrderRepository orderRepository,
                                         EventCodec eventCodec, EventPublisher eventPublisher) {
        this.persistence = persistence;
        this.orderRepository = orderRepository;
        this.eventCodec = eventCodec;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = KafkaTopics.INVENTORY_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        switch (envelope.eventType()) {
            case EventTypes.INVENTORY_RESERVED -> onInventoryReserved(envelope);
            case EventTypes.INVENTORY_RESERVATION_FAILED -> onInventoryReservationFailed(envelope);
            // InventoryReleased has no Order Service consumer in v1 (event-catalog.md §3) — ignored.
            default -> { /* not one of ours */ }
        }
    }

    private void onInventoryReserved(EventEnvelope<JsonNode> envelope) {
        InventoryReservedPayload payload = eventCodec.payloadAs(envelope, InventoryReservedPayload.class);
        String orderId = payload.orderId();

        persistence.appendStatus(orderId, OrderStatus.INVENTORY_RESERVED, envelope.eventId());
        persistence.appendStatus(orderId, OrderStatus.PAYMENT_PENDING, null); // internal transition 4

        var order = orderRepository.findById(orderId).orElseThrow();
        UUID eventId = UUID.randomUUID();
        PaymentRequestedPayload requestPayload =
                new PaymentRequestedPayload(orderId, order.getTotalAmount(), eventId);
        eventPublisher.publish(KafkaTopics.ORDERS_EVENTS, EventTypes.PAYMENT_REQUESTED, orderId, eventId, requestPayload);
    }

    private void onInventoryReservationFailed(EventEnvelope<JsonNode> envelope) {
        InventoryReservationFailedPayload payload =
                eventCodec.payloadAs(envelope, InventoryReservationFailedPayload.class);
        persistence.appendStatus(payload.orderId(), OrderStatus.REJECTED_OUT_OF_STOCK, envelope.eventId());
    }
}

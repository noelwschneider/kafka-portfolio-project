package com.orderfulfillment.order;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Order Service's consumer for {@code payments.events}, driving transitions 5/6 and, on
 * authorization, the internal transition 7 (docs/order-state-machine.md). Same consumer group as
 * {@link OrderInventoryEventsConsumer} ("order-service") — one logical Order Service instance,
 * subscribed to several topics.
 */
@Component
public class OrderPaymentEventsConsumer {

    private static final String GROUP_ID = "order-service";

    private final OrderPersistence persistence;
    private final EventCodec eventCodec;

    public OrderPaymentEventsConsumer(OrderPersistence persistence, EventCodec eventCodec) {
        this.persistence = persistence;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENTS_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        switch (envelope.eventType()) {
            case EventTypes.PAYMENT_AUTHORIZED -> onPaymentAuthorized(envelope);
            case EventTypes.PAYMENT_REJECTED -> onPaymentRejected(envelope);
            default -> { /* not one of ours */ }
        }
    }

    private void onPaymentAuthorized(EventEnvelope<JsonNode> envelope) {
        PaymentAuthorizedPayload payload = eventCodec.payloadAs(envelope, PaymentAuthorizedPayload.class);
        persistence.appendStatus(payload.orderId(), OrderStatus.PAID, envelope.eventId());
        persistence.appendStatus(payload.orderId(), OrderStatus.FULFILLMENT_PENDING, null); // internal transition 7
    }

    private void onPaymentRejected(EventEnvelope<JsonNode> envelope) {
        PaymentRejectedPayload payload = eventCodec.payloadAs(envelope, PaymentRejectedPayload.class);
        persistence.appendStatus(payload.orderId(), OrderStatus.PAYMENT_FAILED, envelope.eventId());
        // No event published here: Inventory Service independently consumes PaymentRejected off
        // payments.events for its own compensation step (event-catalog.md §3 — Consumed by: Order
        // Service, Inventory Service).
    }
}

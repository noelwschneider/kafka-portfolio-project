package com.orderfulfillment.order;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Order Service's consumer for {@code payments.events}, driving transitions 5/6 and, on
 * authorization, the internal transition 7 (docs/order-state-machine.md). Same consumer group as
 * {@link OrderInventoryEventsConsumer} ("order-service") — one logical Order Service instance,
 * subscribed to several topics.
 *
 * <p>Idempotent per ADR-005, same structure as {@link OrderInventoryEventsConsumer}: one
 * {@code consumer_name} ({@link OrderConsumers#PAYMENT_EVENTS_CONSUMER}) shared by both event
 * types this listener handles (docs/reliability-pattern.md §8 point 3).
 */
@Component
public class OrderPaymentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentEventsConsumer.class);

    private static final String GROUP_ID = "order-service";

    private final OrderPersistence persistence;
    private final EventCodec eventCodec;
    private final ProcessedEventLedger processedEventLedger;

    public OrderPaymentEventsConsumer(OrderPersistence persistence, EventCodec eventCodec,
                                       ProcessedEventLedger processedEventLedger) {
        this.persistence = persistence;
        this.eventCodec = eventCodec;
        this.processedEventLedger = processedEventLedger;
    }

    @KafkaListener(id = OrderConsumers.PAYMENT_EVENTS_LISTENER_ID,
            topics = KafkaTopics.PAYMENTS_EVENTS, groupId = GROUP_ID)
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
        String orderId = payload.orderId();

        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), OrderConsumers.PAYMENT_EVENTS_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of PaymentAuthorized {} for {}", envelope.eventId(), orderId);
            return;
        }

        log.info("Processing PaymentAuthorized {} for order {}", envelope.eventId(), orderId);
        persistence.appendPaymentAuthorizedTransition(orderId, envelope.eventId(), eventKey);
        // Nothing to publish here either way: Fulfillment Service consumes PaymentAuthorized
        // directly off payments.events, independent of Order Service's own consumption of it.
    }

    private void onPaymentRejected(EventEnvelope<JsonNode> envelope) {
        PaymentRejectedPayload payload = eventCodec.payloadAs(envelope, PaymentRejectedPayload.class);
        String orderId = payload.orderId();

        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), OrderConsumers.PAYMENT_EVENTS_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of PaymentRejected {} for {}", envelope.eventId(), orderId);
            return;
        }

        log.info("Processing PaymentRejected {} for order {}", envelope.eventId(), orderId);
        persistence.appendStatus(orderId, OrderStatus.PAYMENT_FAILED, envelope.eventId(), eventKey);
        // No event published here: Inventory Service independently consumes PaymentRejected off
        // payments.events for its own compensation step (event-catalog.md §3 — Consumed by: Order
        // Service, Inventory Service).
    }
}

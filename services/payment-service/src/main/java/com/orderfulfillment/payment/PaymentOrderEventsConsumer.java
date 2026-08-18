package com.orderfulfillment.payment;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
import com.orderfulfillment.common.events.PaymentRequestedPayload;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventPublisher;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.time.Instant;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Payment Service's consumer for {@code orders.events} — reacts to PaymentRequested (published by
 * Order Service, not Payment Service — deliberate, see event-catalog.md §2) by running the
 * deterministic payment simulator and publishing the outcome on {@code payments.events}. */
@Component
public class PaymentOrderEventsConsumer {

    private static final String GROUP_ID = "payment-service";

    private final PaymentService paymentService;
    private final EventCodec eventCodec;
    private final EventPublisher eventPublisher;

    public PaymentOrderEventsConsumer(PaymentService paymentService, EventCodec eventCodec,
                                       EventPublisher eventPublisher) {
        this.paymentService = paymentService;
        this.eventCodec = eventCodec;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = KafkaTopics.ORDERS_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        // orders.events also carries OrderCreated, which Payment Service has no use for.
        if (!EventTypes.PAYMENT_REQUESTED.equals(envelope.eventType())) {
            return;
        }
        PaymentRequestedPayload payload = eventCodec.payloadAs(envelope, PaymentRequestedPayload.class);
        String orderId = payload.orderId();

        PaymentOutcome outcome = paymentService.authorize(orderId, payload.amount(), payload.idempotencyKey());
        switch (outcome.kind()) {
            case AUTHORIZED -> {
                PaymentAuthorizedPayload authorized = new PaymentAuthorizedPayload(
                        orderId, outcome.paymentAttemptId(), payload.amount(), Instant.now());
                eventPublisher.publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_AUTHORIZED, orderId, authorized);
            }
            case REJECTED -> {
                PaymentRejectedPayload rejected = new PaymentRejectedPayload(orderId, outcome.paymentAttemptId(),
                        payload.amount(), outcome.failureReason().name(), Instant.now());
                eventPublisher.publish(KafkaTopics.PAYMENTS_EVENTS, EventTypes.PAYMENT_REJECTED, orderId, rejected);
            }
            case PROVIDER_ERROR -> throw new PaymentProviderException(orderId, outcome.paymentAttemptId());
        }
    }
}

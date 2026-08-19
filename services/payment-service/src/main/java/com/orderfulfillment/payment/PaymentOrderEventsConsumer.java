package com.orderfulfillment.payment;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
import com.orderfulfillment.common.events.PaymentRequestedPayload;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventPublisher;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Payment Service's consumer for {@code orders.events} — reacts to PaymentRequested (published by
 * Order Service, not Payment Service — deliberate, see event-catalog.md §2) by running the
 * deterministic payment simulator and publishing the outcome on {@code payments.events}.
 *
 * <p>Idempotent per ADR-005, the same shape docs/reliability-pattern.md asks every fan-out service
 * to copy. The {@link ProcessedEventLedger#isProcessed} check here is only a cheap early-out; the
 * decision that matters is the claim inside {@link PaymentService#authorize}'s own transaction, one
 * layer down, because that is the only place it can commit atomically with the
 * {@code payment_attempts} row it guards. Events this consumer has no use for (OrderCreated, also
 * carried on {@code orders.events}) are skipped before the ledger is touched.
 */
@Component
public class PaymentOrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderEventsConsumer.class);

    private static final String GROUP_ID = "payment-service";

    private final PaymentService paymentService;
    private final EventCodec eventCodec;
    private final EventPublisher eventPublisher;
    private final ProcessedEventLedger processedEventLedger;

    public PaymentOrderEventsConsumer(PaymentService paymentService, EventCodec eventCodec,
                                       EventPublisher eventPublisher,
                                       ProcessedEventLedger processedEventLedger) {
        this.paymentService = paymentService;
        this.eventCodec = eventCodec;
        this.eventPublisher = eventPublisher;
        this.processedEventLedger = processedEventLedger;
    }

    @KafkaListener(id = PaymentConsumers.PAYMENT_REQUESTED_LISTENER_ID,
            topics = KafkaTopics.ORDERS_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        // orders.events also carries OrderCreated, which Payment Service has no use for.
        if (!EventTypes.PAYMENT_REQUESTED.equals(envelope.eventType())) {
            return;
        }
        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), PaymentConsumers.PAYMENT_REQUESTED_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of PaymentRequested {} for {}",
                    envelope.eventId(), envelope.aggregateId());
            return;
        }

        PaymentRequestedPayload payload = eventCodec.payloadAs(envelope, PaymentRequestedPayload.class);
        String orderId = payload.orderId();
        log.info("Processing PaymentRequested {} for order {}", envelope.eventId(), orderId);

        PaymentOutcome outcome =
                paymentService.authorize(orderId, payload.amount(), payload.idempotencyKey(), eventKey);
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
            case DUPLICATE -> {
                // A concurrent delivery of the same event won the ledger claim; it publishes the
                // outcome. Nothing to do here.
            }
        }
    }
}

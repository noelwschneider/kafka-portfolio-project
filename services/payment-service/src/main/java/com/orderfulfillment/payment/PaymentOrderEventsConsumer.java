package com.orderfulfillment.payment;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentRequestedPayload;
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
 *
 * <p><b>Sprint 2:</b> this consumer no longer publishes anything itself. {@link PaymentService}
 * records the outbound PaymentAuthorized/PaymentRejected event to {@code outbox_events} inside the
 * same transaction as the {@code payment_attempts} row, per ADR-006; {@link OutboxPublisher} sends
 * it to Kafka afterward.
 */
@Component
public class PaymentOrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrderEventsConsumer.class);

    private static final String GROUP_ID = "payment-service";

    private final PaymentService paymentService;
    private final EventCodec eventCodec;
    private final ProcessedEventLedger processedEventLedger;

    public PaymentOrderEventsConsumer(PaymentService paymentService, EventCodec eventCodec,
                                       ProcessedEventLedger processedEventLedger) {
        this.paymentService = paymentService;
        this.eventCodec = eventCodec;
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

        // AUTHORIZED/REJECTED outcomes and their outbox row are written atomically inside
        // PaymentService (ADR-006, Sprint 2) — nothing left to publish here. PROVIDER_ERROR still
        // throws to drive the consumer error handler's retry/DLQ path; DUPLICATE means a concurrent
        // delivery of the same event already recorded (and will publish) the outcome.
        PaymentOutcome outcome =
                paymentService.authorize(orderId, payload.amount(), payload.idempotencyKey(), eventKey);
        if (outcome.kind() == PaymentOutcome.Kind.PROVIDER_ERROR) {
            throw new PaymentProviderException(orderId, outcome.paymentAttemptId());
        }
    }
}

package com.orderfulfillment.fulfillment;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
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
 * Fulfillment Service's consumer for {@code payments.events} — reacts to PaymentAuthorized
 * independently of Order Service's own consumer of the same event (the project's one deliberate
 * fan-out, event-catalog.md §3): own consumer group ("fulfillment-service") so both see every
 * record without either waiting on the other.
 *
 * <p>Idempotent per ADR-005, following the same shape as Inventory Service's
 * {@code InventoryOrderEventsConsumer} (docs/reliability-pattern.md). The
 * {@link ProcessedEventLedger#isProcessed} check here is only a cheap early-out; the decision that
 * matters is the claim inside {@link FulfillmentService#createShipment}'s own transaction, one layer
 * down, because that is the only place it can commit atomically with the side effect. Events this
 * consumer has no use for (PaymentRejected) are skipped before the ledger is touched.
 *
 * <p><b>Sprint 2:</b> this consumer no longer publishes anything itself. {@link FulfillmentService}
 * records the outbound ShipmentCreated event to {@code outbox_events} inside the same transaction
 * as the {@code shipments} row, per ADR-006; {@link OutboxPublisher} sends it to Kafka afterward.
 */
@Component
public class FulfillmentPaymentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentPaymentEventsConsumer.class);

    private static final String GROUP_ID = "fulfillment-service";

    private final FulfillmentService fulfillmentService;
    private final EventCodec eventCodec;
    private final ProcessedEventLedger processedEventLedger;

    public FulfillmentPaymentEventsConsumer(FulfillmentService fulfillmentService, EventCodec eventCodec,
                                             ProcessedEventLedger processedEventLedger) {
        this.fulfillmentService = fulfillmentService;
        this.eventCodec = eventCodec;
        this.processedEventLedger = processedEventLedger;
    }

    @KafkaListener(id = FulfillmentConsumers.PAYMENT_AUTHORIZED_LISTENER_ID,
            topics = KafkaTopics.PAYMENTS_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        // payments.events also carries PaymentRejected, which Fulfillment Service ignores.
        if (!EventTypes.PAYMENT_AUTHORIZED.equals(envelope.eventType())) {
            return;
        }
        ProcessedEventKey eventKey = new ProcessedEventKey(
                envelope.eventId(), FulfillmentConsumers.PAYMENT_AUTHORIZED_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of PaymentAuthorized {} for {}",
                    envelope.eventId(), envelope.aggregateId());
            return;
        }

        PaymentAuthorizedPayload payload = eventCodec.payloadAs(envelope, PaymentAuthorizedPayload.class);
        String orderId = payload.orderId();
        log.info("Processing PaymentAuthorized {} for order {}", envelope.eventId(), orderId);

        // The shipment and its ShipmentCreated outbox row are written atomically inside
        // FulfillmentService (ADR-006, Sprint 2) — nothing left to publish here. A DUPLICATE result
        // means a concurrent delivery of the same event already recorded (and will publish) it.
        fulfillmentService.createShipment(orderId, eventKey);
    }
}

package com.orderfulfillment.fulfillment;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.ShipmentCreatedPayload;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventPublisher;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.fulfillment.dto.ShipmentDto;
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
 */
@Component
public class FulfillmentPaymentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentPaymentEventsConsumer.class);

    private static final String GROUP_ID = "fulfillment-service";

    private final FulfillmentService fulfillmentService;
    private final EventCodec eventCodec;
    private final EventPublisher eventPublisher;
    private final ProcessedEventLedger processedEventLedger;

    public FulfillmentPaymentEventsConsumer(FulfillmentService fulfillmentService, EventCodec eventCodec,
                                             EventPublisher eventPublisher,
                                             ProcessedEventLedger processedEventLedger) {
        this.fulfillmentService = fulfillmentService;
        this.eventCodec = eventCodec;
        this.eventPublisher = eventPublisher;
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

        ShipmentCreationResult result = fulfillmentService.createShipment(orderId, eventKey);
        if (result.duplicate()) {
            // A concurrent delivery of the same event won the ledger claim; it publishes the outcome.
            return;
        }
        ShipmentDto shipment = result.shipment();
        ShipmentCreatedPayload created = new ShipmentCreatedPayload(
                orderId, shipment.id(), shipment.trackingNumber(), shipment.createdAt());
        eventPublisher.publish(KafkaTopics.FULFILLMENT_EVENTS, EventTypes.SHIPMENT_CREATED, orderId, created);
    }
}

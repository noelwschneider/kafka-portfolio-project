package com.orderfulfillment.fulfillment;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentAuthorizedPayload;
import com.orderfulfillment.common.events.ShipmentCreatedPayload;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventPublisher;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.fulfillment.dto.ShipmentDto;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Fulfillment Service's consumer for {@code payments.events} — reacts to PaymentAuthorized
 * independently of Order Service's own consumer of the same event (the project's one deliberate
 * fan-out, event-catalog.md §3): own consumer group ("fulfillment-service") so both see every
 * record without either waiting on the other.
 */
@Component
public class FulfillmentPaymentEventsConsumer {

    private static final String GROUP_ID = "fulfillment-service";

    private final FulfillmentService fulfillmentService;
    private final EventCodec eventCodec;
    private final EventPublisher eventPublisher;

    public FulfillmentPaymentEventsConsumer(FulfillmentService fulfillmentService, EventCodec eventCodec,
                                             EventPublisher eventPublisher) {
        this.fulfillmentService = fulfillmentService;
        this.eventCodec = eventCodec;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = KafkaTopics.PAYMENTS_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        // payments.events also carries PaymentRejected, which Fulfillment Service ignores.
        if (!EventTypes.PAYMENT_AUTHORIZED.equals(envelope.eventType())) {
            return;
        }
        PaymentAuthorizedPayload payload = eventCodec.payloadAs(envelope, PaymentAuthorizedPayload.class);
        String orderId = payload.orderId();

        ShipmentDto shipment = fulfillmentService.createShipment(orderId);
        ShipmentCreatedPayload created = new ShipmentCreatedPayload(
                orderId, shipment.id(), shipment.trackingNumber(), shipment.createdAt());
        eventPublisher.publish(KafkaTopics.FULFILLMENT_EVENTS, EventTypes.SHIPMENT_CREATED, orderId, created);
    }
}

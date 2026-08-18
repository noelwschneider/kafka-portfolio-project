package com.orderfulfillment.order;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.ShipmentCreatedPayload;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Order Service's consumer for {@code fulfillment.events}, driving transition 8
 * (docs/order-state-machine.md) — the happy path's terminal state. */
@Component
public class OrderFulfillmentEventsConsumer {

    private static final String GROUP_ID = "order-service";

    private final OrderPersistence persistence;
    private final EventCodec eventCodec;

    public OrderFulfillmentEventsConsumer(OrderPersistence persistence, EventCodec eventCodec) {
        this.persistence = persistence;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = KafkaTopics.FULFILLMENT_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        if (EventTypes.SHIPMENT_CREATED.equals(envelope.eventType())) {
            ShipmentCreatedPayload payload = eventCodec.payloadAs(envelope, ShipmentCreatedPayload.class);
            persistence.appendStatus(payload.orderId(), OrderStatus.FULFILLED, envelope.eventId());
        }
    }
}

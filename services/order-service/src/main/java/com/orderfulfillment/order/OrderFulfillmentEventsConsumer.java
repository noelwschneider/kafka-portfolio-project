package com.orderfulfillment.order;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.ShipmentCreatedPayload;
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
 * Order Service's consumer for {@code fulfillment.events}, driving transition 8
 * (docs/order-state-machine.md) — the happy path's terminal state.
 *
 * <p>Idempotent per ADR-005, same structure as the other two consumers, with a single event type
 * ({@code ShipmentCreated}) and so a single {@code consumer_name}
 * ({@link OrderConsumers#FULFILLMENT_EVENTS_CONSUMER}).
 */
@Component
public class OrderFulfillmentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentEventsConsumer.class);

    private static final String GROUP_ID = "order-service";

    private final OrderPersistence persistence;
    private final EventCodec eventCodec;
    private final ProcessedEventLedger processedEventLedger;

    public OrderFulfillmentEventsConsumer(OrderPersistence persistence, EventCodec eventCodec,
                                           ProcessedEventLedger processedEventLedger) {
        this.persistence = persistence;
        this.eventCodec = eventCodec;
        this.processedEventLedger = processedEventLedger;
    }

    @KafkaListener(id = OrderConsumers.FULFILLMENT_EVENTS_LISTENER_ID,
            topics = KafkaTopics.FULFILLMENT_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        // fulfillment.events carries only ShipmentCreated today (event-catalog.md §3), but filter
        // explicitly anyway rather than relying on that — a skipped record has no side effect to
        // deduplicate, and recording it would fill the ledger with rows for events this service
        // never acts on.
        if (!EventTypes.SHIPMENT_CREATED.equals(envelope.eventType())) {
            return;
        }
        ShipmentCreatedPayload payload = eventCodec.payloadAs(envelope, ShipmentCreatedPayload.class);
        String orderId = payload.orderId();

        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), OrderConsumers.FULFILLMENT_EVENTS_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of ShipmentCreated {} for {}", envelope.eventId(), orderId);
            return;
        }

        persistence.appendStatus(orderId, OrderStatus.FULFILLED, envelope.eventId(), eventKey);
    }
}

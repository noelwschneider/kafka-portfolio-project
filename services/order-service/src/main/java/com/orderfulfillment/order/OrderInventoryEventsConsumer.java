package com.orderfulfillment.order;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.InventoryReservationFailedPayload;
import com.orderfulfillment.common.events.InventoryReservedPayload;
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
 * Order Service's consumer for {@code inventory.events}, driving transitions 2/3 of
 * docs/order-state-machine.md and, on success, transition 4 (whose PaymentRequested is recorded in
 * the outbox by the same transaction — ADR-006, Phase 6).
 * Own consumer group ("order-service") so Order Service reads every record on this topic
 * independently of any other consumer group.
 *
 * <p>Idempotent per ADR-005, the reference shape docs/reliability-pattern.md asks every fan-out
 * service to copy. This listener handles two event types (InventoryReserved,
 * InventoryReservationFailed) but uses a single {@code consumer_name}
 * ({@link OrderConsumers#INVENTORY_EVENTS_CONSUMER}) for both — one ledger namespace per listener
 * method, not per event type, matching Inventory Service (docs/reliability-pattern.md §8 point 3).
 * InventoryReleased, the topic's third event type, has no Order Service consumer in v1
 * (event-catalog.md §3) and is filtered out in {@link #handle} before the ledger is ever touched.
 */
@Component
public class OrderInventoryEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderInventoryEventsConsumer.class);

    private static final String GROUP_ID = "order-service";

    private final OrderPersistence persistence;
    private final EventCodec eventCodec;
    private final ProcessedEventLedger processedEventLedger;

    public OrderInventoryEventsConsumer(OrderPersistence persistence, EventCodec eventCodec,
                                         ProcessedEventLedger processedEventLedger) {
        this.persistence = persistence;
        this.eventCodec = eventCodec;
        this.processedEventLedger = processedEventLedger;
    }

    @KafkaListener(id = OrderConsumers.INVENTORY_EVENTS_LISTENER_ID,
            topics = KafkaTopics.INVENTORY_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        switch (envelope.eventType()) {
            case EventTypes.INVENTORY_RESERVED -> onInventoryReserved(envelope);
            case EventTypes.INVENTORY_RESERVATION_FAILED -> onInventoryReservationFailed(envelope);
            // InventoryReleased has no Order Service consumer in v1 (event-catalog.md §3) — ignored,
            // and filtered here before the ledger is touched: a skipped record has no side effect to
            // deduplicate.
            default -> { /* not one of ours */ }
        }
    }

    private void onInventoryReserved(EventEnvelope<JsonNode> envelope) {
        InventoryReservedPayload payload = eventCodec.payloadAs(envelope, InventoryReservedPayload.class);
        String orderId = payload.orderId();

        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), OrderConsumers.INVENTORY_EVENTS_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of InventoryReserved {} for {}", envelope.eventId(), orderId);
            return;
        }

        // PaymentRequested is recorded in the outbox by that same transaction (ADR-006 + the method's
        // Javadoc), so there is nothing left to publish here — and, crucially, no window in which
        // this consumer could have committed the processed_events claim without the event.
        persistence.appendInventoryReservedTransition(orderId, envelope.eventId(), eventKey);
    }

    private void onInventoryReservationFailed(EventEnvelope<JsonNode> envelope) {
        InventoryReservationFailedPayload payload =
                eventCodec.payloadAs(envelope, InventoryReservationFailedPayload.class);
        String orderId = payload.orderId();

        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), OrderConsumers.INVENTORY_EVENTS_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of InventoryReservationFailed {} for {}",
                    envelope.eventId(), orderId);
            return;
        }

        persistence.appendStatus(orderId, OrderStatus.REJECTED_OUT_OF_STOCK, envelope.eventId(), eventKey);
    }
}

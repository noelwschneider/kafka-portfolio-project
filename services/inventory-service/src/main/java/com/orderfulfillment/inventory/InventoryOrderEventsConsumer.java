package com.orderfulfillment.inventory;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.InventoryReservationFailedPayload;
import com.orderfulfillment.common.events.InventoryReservedPayload;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.events.ShortageItem;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventPublisher;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inventory Service's consumer for {@code orders.events} — reacts to OrderCreated by attempting
 * an all-or-nothing reservation and publishing the result on {@code inventory.events}
 * (docs/events/event-catalog.md §3).
 *
 * <p>Idempotent per ADR-005, and the reference shape docs/reliability-pattern.md asks the other
 * three services to copy. Two things about the structure are load-bearing:
 * <ul>
 *   <li>The {@link ProcessedEventLedger#isProcessed} check here is only a cheap early-out. The
 *       decision that matters is made by the claim inside the reservation's own transaction, one
 *       layer down, because that is the only place it can commit atomically with the side effect.</li>
 *   <li>Events this consumer has no use for are skipped <em>before</em> the ledger is touched. A
 *       skipped record has no side effect to deduplicate, and recording it would fill the ledger
 *       with rows for events this service never acts on.</li>
 * </ul>
 */
@Component
public class InventoryOrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryOrderEventsConsumer.class);

    private static final String GROUP_ID = "inventory-service";

    private final InventoryService inventoryService;
    private final EventCodec eventCodec;
    private final EventPublisher eventPublisher;
    private final ProcessedEventLedger processedEventLedger;

    public InventoryOrderEventsConsumer(InventoryService inventoryService, EventCodec eventCodec,
                                         EventPublisher eventPublisher,
                                         ProcessedEventLedger processedEventLedger) {
        this.inventoryService = inventoryService;
        this.eventCodec = eventCodec;
        this.eventPublisher = eventPublisher;
        this.processedEventLedger = processedEventLedger;
    }

    @KafkaListener(id = InventoryConsumers.ORDER_CREATED_LISTENER_ID,
            topics = KafkaTopics.ORDERS_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        // orders.events also carries PaymentRequested, which Inventory Service has no use for.
        if (!EventTypes.ORDER_CREATED.equals(envelope.eventType())) {
            return;
        }
        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), InventoryConsumers.ORDER_CREATED_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of OrderCreated {} for {}",
                    envelope.eventId(), envelope.aggregateId());
            return;
        }

        OrderCreatedPayload payload = eventCodec.payloadAs(envelope, OrderCreatedPayload.class);
        String orderId = payload.orderId();
        List<OrderLine> lines = payload.items().stream()
                .map(i -> new OrderLine(i.sku(), i.quantity())).toList();

        ReservationResult result = inventoryService.reserve(orderId, lines, eventKey);
        if (result.duplicate()) {
            // A concurrent delivery of the same event won the ledger claim; it publishes the outcome.
            return;
        }
        if (result.success()) {
            List<EventItem> items = payload.items();
            InventoryReservedPayload reserved =
                    new InventoryReservedPayload(orderId, result.reservationId(), items, Instant.now());
            eventPublisher.publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVED, orderId, reserved);
        } else {
            List<ShortageItem> shortages = result.shortages().stream()
                    .map(s -> new ShortageItem(s.sku(), s.requested(), s.available())).toList();
            InventoryReservationFailedPayload failed =
                    new InventoryReservationFailedPayload(orderId, result.failureReason(), shortages);
            eventPublisher.publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RESERVATION_FAILED, orderId, failed);
        }
    }
}

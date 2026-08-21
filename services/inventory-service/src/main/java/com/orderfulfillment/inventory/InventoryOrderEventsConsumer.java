package com.orderfulfillment.inventory;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.common.kafka.EventCodec;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
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
 *
 * <p><b>Sprint 2:</b> this consumer no longer publishes anything itself. {@link InventoryService}
 * (by way of {@link InventoryReservationExecutor}) records the outbound InventoryReserved /
 * InventoryReservationFailed event to {@code outbox_events} inside the same transaction as the
 * reservation, per ADR-006; {@link OutboxPublisher} sends it to Kafka afterward.
 */
@Component
public class InventoryOrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryOrderEventsConsumer.class);

    private static final String GROUP_ID = "inventory-service";

    private final InventoryService inventoryService;
    private final EventCodec eventCodec;
    private final ProcessedEventLedger processedEventLedger;

    public InventoryOrderEventsConsumer(InventoryService inventoryService, EventCodec eventCodec,
                                         ProcessedEventLedger processedEventLedger) {
        this.inventoryService = inventoryService;
        this.eventCodec = eventCodec;
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
        log.info("Processing OrderCreated {} for order {}", envelope.eventId(), orderId);
        List<OrderLine> lines = payload.items().stream()
                .map(i -> new OrderLine(i.sku(), i.quantity())).toList();

        // The reservation (or shortage) outcome and its InventoryReserved/InventoryReservationFailed
        // outbox row are written atomically inside InventoryService/InventoryReservationExecutor
        // (ADR-006, Sprint 2) — nothing left to publish here. A DUPLICATE result means a concurrent
        // delivery of the same event already recorded (and will publish) the outcome.
        inventoryService.reserve(orderId, lines, eventKey);
    }
}

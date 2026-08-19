package com.orderfulfillment.inventory;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.InventoryReleasedPayload;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
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
 * Inventory Service's consumer for {@code payments.events} — the compensation trigger:
 * PaymentRejected releases the order's reservation and publishes InventoryReleased
 * (docs/events/event-catalog.md §3). Own consumer group ("inventory-service"), independent of
 * Order Service's group on the same topic.
 *
 * <p>Idempotent per ADR-005, with the same structure as {@link InventoryOrderEventsConsumer}. Note
 * the different {@code consumer_name} ({@code inventory.payment-rejected}): the composite ledger key
 * is what allows this consumer and the OrderCreated one to record the same {@code eventId}
 * independently, which matters because a single {@code processed_events} table serves both.
 */
@Component
public class InventoryPaymentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryPaymentEventsConsumer.class);

    private static final String GROUP_ID = "inventory-service";

    private final InventoryService inventoryService;
    private final EventCodec eventCodec;
    private final EventPublisher eventPublisher;
    private final ProcessedEventLedger processedEventLedger;

    public InventoryPaymentEventsConsumer(InventoryService inventoryService, EventCodec eventCodec,
                                           EventPublisher eventPublisher,
                                           ProcessedEventLedger processedEventLedger) {
        this.inventoryService = inventoryService;
        this.eventCodec = eventCodec;
        this.eventPublisher = eventPublisher;
        this.processedEventLedger = processedEventLedger;
    }

    @KafkaListener(id = InventoryConsumers.PAYMENT_REJECTED_LISTENER_ID,
            topics = KafkaTopics.PAYMENTS_EVENTS, groupId = GROUP_ID)
    public void onMessage(String message) {
        EventEnvelope<JsonNode> envelope = eventCodec.decode(message);
        CorrelationIdHolder.runInScope(envelope.correlationId(), () -> handle(envelope));
    }

    private void handle(EventEnvelope<JsonNode> envelope) {
        // payments.events also carries PaymentAuthorized, which Inventory Service ignores.
        if (!EventTypes.PAYMENT_REJECTED.equals(envelope.eventType())) {
            return;
        }
        ProcessedEventKey eventKey =
                new ProcessedEventKey(envelope.eventId(), InventoryConsumers.PAYMENT_REJECTED_CONSUMER);
        if (processedEventLedger.isProcessed(eventKey)) {
            log.info("Skipping duplicate delivery of PaymentRejected {} for {}",
                    envelope.eventId(), envelope.aggregateId());
            return;
        }

        PaymentRejectedPayload payload = eventCodec.payloadAs(envelope, PaymentRejectedPayload.class);
        String orderId = payload.orderId();
        log.info("Processing PaymentRejected {} for order {}", envelope.eventId(), orderId);

        ReleaseResult result = inventoryService.release(orderId, eventKey);
        if (result.duplicate()) {
            return; // a concurrent delivery of the same event won the ledger claim
        }
        if (result.reservationId() == null) {
            return; // nothing was reserved for this order (should not happen on the real flow) — nothing to publish
        }
        List<EventItem> items = result.items().stream()
                .map(line -> new EventItem(line.sku(), line.quantity())).toList();
        InventoryReleasedPayload released = new InventoryReleasedPayload(
                orderId, result.reservationId(), items, "PAYMENT_REJECTED", Instant.now());
        eventPublisher.publish(KafkaTopics.INVENTORY_EVENTS, EventTypes.INVENTORY_RELEASED, orderId, released);
    }
}

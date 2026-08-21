package com.orderfulfillment.inventory;

import tools.jackson.databind.JsonNode;
import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.events.PaymentRejectedPayload;
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
 * Inventory Service's consumer for {@code payments.events} — the compensation trigger:
 * PaymentRejected releases the order's reservation and publishes InventoryReleased
 * (docs/events/event-catalog.md §3). Own consumer group ("inventory-service"), independent of
 * Order Service's group on the same topic.
 *
 * <p>Idempotent per ADR-005, with the same structure as {@link InventoryOrderEventsConsumer}. Note
 * the different {@code consumer_name} ({@code inventory.payment-rejected}): the composite ledger key
 * is what allows this consumer and the OrderCreated one to record the same {@code eventId}
 * independently, which matters because a single {@code processed_events} table serves both.
 *
 * <p><b>Sprint 2:</b> this consumer no longer publishes anything itself — see
 * {@link InventoryOrderEventsConsumer}'s Javadoc; the same ADR-006 outbox change applies here.
 */
@Component
public class InventoryPaymentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryPaymentEventsConsumer.class);

    private static final String GROUP_ID = "inventory-service";

    private final InventoryService inventoryService;
    private final EventCodec eventCodec;
    private final ProcessedEventLedger processedEventLedger;

    public InventoryPaymentEventsConsumer(InventoryService inventoryService, EventCodec eventCodec,
                                           ProcessedEventLedger processedEventLedger) {
        this.inventoryService = inventoryService;
        this.eventCodec = eventCodec;
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

        // The release and its InventoryReleased outbox row (when there was anything to release) are
        // written atomically inside InventoryService/InventoryReservationExecutor (ADR-006,
        // Sprint 2) — nothing left to publish here.
        inventoryService.release(orderId, eventKey);
    }
}

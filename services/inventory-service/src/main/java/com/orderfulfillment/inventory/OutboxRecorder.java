package com.orderfulfillment.inventory;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.kafka.EventPublisher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The write half of ADR-006's transactional outbox, extended to Inventory Service in Sprint 2 (the
 * gap ADR-006 originally left open — "Inventory, Payment and Fulfillment Service still ship
 * [publish-after-commit]"). Same shape as Order Service's {@code OutboxRecorder}: turns an event
 * into a row in {@code outbox_events} instead of a Kafka send, so that the reservation change and
 * "the event will be published" become the same commit.
 *
 * <p>{@link Propagation#MANDATORY} for the same reason Order Service's copy uses it: an outbox
 * insert in a transaction of its own would reintroduce the dual-write window this class exists to
 * close, so calling it without one fails loudly at the call site. In practice that call site is
 * always {@link InventoryReservationExecutor}'s {@code REQUIRES_NEW} methods, the same transaction
 * that claims the {@code processed_events} ledger row.
 */
@Component
class OutboxRecorder {

    private final OutboxEventRepository outboxRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    OutboxRecorder(OutboxEventRepository outboxRepository, EventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /** Records an event with a freshly generated eventId. Nothing published by this service needs a
     * caller-supplied eventId (unlike Order Service's PaymentRequested), so this is the only overload. */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID record(String eventType, String aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope<Object> envelope = eventPublisher.buildEnvelope(eventType, aggregateId, eventId, payload);
        String json = objectMapper.writeValueAsString(envelope);
        outboxRepository.save(new OutboxEventEntity(aggregateId, eventType, json, Instant.now()));
        return eventId;
    }
}

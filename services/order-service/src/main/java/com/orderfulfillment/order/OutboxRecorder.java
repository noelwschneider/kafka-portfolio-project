package com.orderfulfillment.order;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.kafka.EventPublisher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The write half of ADR-006's transactional outbox: turns an event into a row in {@code
 * outbox_events} instead of a Kafka send, so that "the order exists" and "the event will be
 * published" become the same commit.
 *
 * <p>The envelope is built <em>here</em>, at business-transaction time, not later by
 * {@link OutboxDispatcher} — so {@code eventId}, {@code occurredAt} and {@code correlationId}
 * describe the moment the change actually happened, and are identical no matter how many times the
 * dispatcher has to resend the row. It also means the eventId is known to the caller before the
 * send, which PaymentRequested needs (its {@code idempotencyKey} is its own eventId,
 * docs/events/event-catalog.md §3).
 *
 * <p>{@link Propagation#MANDATORY} for the same reason
 * {@link com.orderfulfillment.common.idempotency.ProcessedEventLedger#recordProcessed} uses it: an
 * outbox insert in a transaction of its own would reintroduce exactly the dual-write window this
 * class exists to close, so calling it without one fails loudly at the call site.
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

    /** Records an event with a freshly generated eventId, returning it. */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID record(String eventType, String aggregateId, Object payload) {
        return record(eventType, aggregateId, UUID.randomUUID(), payload);
    }

    /**
     * Records an event whose eventId the caller already knows — the payload-references-its-own-id
     * case ({@code PaymentRequested.idempotencyKey}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    UUID record(String eventType, String aggregateId, UUID eventId, Object payload) {
        EventEnvelope<Object> envelope = eventPublisher.buildEnvelope(eventType, aggregateId, eventId, payload);
        String json = objectMapper.writeValueAsString(envelope);
        outboxRepository.save(new OutboxEventEntity(aggregateId, eventType, json, Instant.now()));
        return eventId;
    }
}

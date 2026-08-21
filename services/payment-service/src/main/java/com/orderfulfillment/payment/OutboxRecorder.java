package com.orderfulfillment.payment;

import com.orderfulfillment.common.events.EventEnvelope;
import com.orderfulfillment.common.kafka.EventPublisher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The write half of ADR-006's transactional outbox, extended to Payment Service in Sprint 2. Same
 * shape as Order Service's {@code OutboxRecorder} — see that class's Javadoc. {@link
 * Propagation#MANDATORY} because an outbox insert in its own transaction would reintroduce the
 * dual-write window this class exists to close; the call site is always {@link PaymentService
 * #authorize}'s {@code REQUIRES_NEW} transaction, the same one that claims the
 * {@code processed_events} ledger row.
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

    @Transactional(propagation = Propagation.MANDATORY)
    UUID record(String eventType, String aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope<Object> envelope = eventPublisher.buildEnvelope(eventType, aggregateId, eventId, payload);
        String json = objectMapper.writeValueAsString(envelope);
        outboxRepository.save(new OutboxEventEntity(aggregateId, eventType, json, Instant.now()));
        return eventId;
    }
}

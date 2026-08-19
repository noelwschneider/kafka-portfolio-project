package com.orderfulfillment.common.kafka;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventEnvelope;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Wraps every outbound record in the frozen envelope (docs/events/event-catalog.md §1) and sends it
 * keyed by aggregateId (= orderId), per §2. correlationId is read from {@link CorrelationIdHolder}
 * rather than threaded through every call site — the same plumbing
 * {@code CorrelationIdFilter} already uses for HTTP, extended here to also carry the id through the
 * Kafka hop: HTTP request handling sets it via the filter, and each @KafkaListener sets it from the
 * envelope it just consumed (see {@code CorrelationIdHolder#runInScope}) before calling back into
 * this publisher.
 *
 * <p>{@link #publish} is publish-after-commit, not atomic with the business transaction — a known,
 * accepted gap (ADR-006). {@code kafkaTemplate.send} is fire-and-forget here (not blocked on); a
 * send failure is logged by the Kafka client but does not roll back the already-committed local
 * change. Phase 6 closed that gap in Order Service only, by routing its two publish sites through
 * the {@code outbox_events} table instead of {@link #publish} (see {@code OutboxRecorder} /
 * {@code OutboxDispatcher} there); Inventory, Payment and Fulfillment Service still publish this
 * way, deliberately and documented.
 */
@Component
public class EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /** Publishes with a freshly generated eventId. Returns the eventId, in case the caller (e.g.
     * PaymentRequested, whose idempotencyKey IS this event's eventId) needs it. */
    public UUID publish(String topic, String eventType, String aggregateId, Object payload) {
        return publish(topic, eventType, aggregateId, UUID.randomUUID(), payload);
    }

    /** Publishes with a caller-supplied eventId — used when the payload itself needs to reference
     * this event's id before it is sent (PaymentRequested.idempotencyKey, event-catalog.md §3). */
    public UUID publish(String topic, String eventType, String aggregateId, UUID eventId, Object payload) {
        String json = objectMapper.writeValueAsString(buildEnvelope(eventType, aggregateId, eventId, payload));
        kafkaTemplate.send(topic, aggregateId, json);
        return eventId;
    }

    /**
     * Builds the envelope without sending it. Exists for Order Service's Phase 6 transactional
     * outbox (ADR-006), which must stamp eventId/occurredAt/correlationId at the moment of the
     * business transaction and store the result, leaving the actual send to a background publisher.
     * Kept here rather than copied into that service so there is exactly one place the frozen
     * envelope of docs/events/event-catalog.md §1 is constructed.
     */
    public EventEnvelope<Object> buildEnvelope(String eventType, String aggregateId, UUID eventId, Object payload) {
        UUID correlationId = CorrelationIdHolder.get();
        if (correlationId == null) {
            throw new IllegalStateException("No correlationId in scope while publishing " + eventType
                    + " — every publish site must run within an HTTP request or a @KafkaListener that set one");
        }
        return new EventEnvelope<>(
                eventId, eventType, EventTypes.CURRENT_VERSION, Instant.now(), correlationId, aggregateId, payload);
    }
}

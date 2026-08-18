package com.orderfulfillment.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The frozen envelope every published Kafka record uses, per docs/events/event-catalog.md §1.
 * {@code payload} is generic: producers build an envelope over a concrete payload record;
 * consumers first deserialize with {@code payload} typed as {@link tools.jackson.databind.JsonNode}
 * (see {@code EventCodec}) and then convert it to the concrete payload type once {@code eventType}
 * is known, since a single topic can carry more than one event type.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID correlationId,
        String aggregateId,
        T payload
) {
}

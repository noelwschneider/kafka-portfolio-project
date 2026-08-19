package com.orderfulfillment.scenario.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Backs the new `GET /demo/events` Event Explorer query endpoint (Phase 5 addition). */
public record EventRecordDto(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID correlationId,
        String aggregateId,
        String topic,
        Integer partition,
        Long offset,
        String producer,
        boolean deadLettered,
        Map<String, Object> payload
) {
}

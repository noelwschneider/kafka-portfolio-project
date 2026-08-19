package com.orderfulfillment.scenario.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScenarioRunDto(
        String id,
        String scenarioName,
        String status,
        UUID correlationId,
        String orderId,
        Instant startedAt,
        Instant completedAt,
        Long elapsedMs,
        String errorMessage,
        List<ScenarioTimelineEntryDto> timeline
) {
}

package com.orderfulfillment.scenario.dto;

import java.time.Instant;
import java.util.UUID;

public record ScenarioRunSummaryDto(
        String id,
        String scenarioName,
        String status,
        UUID correlationId,
        String orderId,
        Instant startedAt,
        Instant completedAt,
        Long elapsedMs
) {
}

package com.orderfulfillment.scenario.dto;

import java.time.Instant;
import java.util.Map;

public record ScenarioTimelineEntryDto(
        int sequence,
        String kind,
        String label,
        Instant occurredAt,
        Map<String, Object> detail
) {
}

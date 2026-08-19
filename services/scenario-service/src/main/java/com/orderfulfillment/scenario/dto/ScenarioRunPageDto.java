package com.orderfulfillment.scenario.dto;

import java.util.List;

public record ScenarioRunPageDto(
        List<ScenarioRunSummaryDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}

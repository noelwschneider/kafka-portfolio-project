package com.orderfulfillment.scenario.dto;

import java.util.List;

public record ScenarioDefinitionDto(
        String name,
        String title,
        String description,
        List<String> demonstrates,
        String expectedTerminalStatus,
        boolean available
) {
}

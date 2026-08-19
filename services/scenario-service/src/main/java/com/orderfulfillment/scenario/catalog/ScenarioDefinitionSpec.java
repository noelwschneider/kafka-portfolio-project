package com.orderfulfillment.scenario.catalog;

import java.util.List;

/**
 * Internal representation of one row of docs/scenarios.md's Index table, kept as data rather than
 * duplicated prose so {@code GET /demo/scenarios} (via {@link ScenarioCatalog}) cannot drift from
 * what {@link com.orderfulfillment.scenario.scenarios.ScenarioRunner} actually does.
 */
public record ScenarioDefinitionSpec(
        String name,
        String title,
        String description,
        List<String> demonstrates,
        String expectedTerminalStatus,
        boolean available
) {
}

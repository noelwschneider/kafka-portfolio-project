package com.orderfulfillment.scenario.scenarios;

import java.util.UUID;
import java.util.function.Consumer;

/** What one {@link ScenarioRunner} invocation gets: its identity, and a way to report its primary order. */
public record ScenarioRunContext(String runId, UUID correlationId, Consumer<String> setPrimaryOrderId) {
}

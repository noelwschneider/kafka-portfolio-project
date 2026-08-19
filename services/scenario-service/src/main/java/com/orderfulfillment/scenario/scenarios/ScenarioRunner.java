package com.orderfulfillment.scenario.scenarios;

/** One implementation per in-scope scenario in docs/scenarios.md, dispatched by {@link ScenarioExecutionService}. */
public interface ScenarioRunner {

    /** The {@link com.orderfulfillment.scenario.catalog.ScenarioCatalog} name this runner implements. */
    String scenarioName();

    /**
     * Runs the scenario to completion. Called on the scenario-executor thread, already wrapped in
     * {@code CorrelationIdHolder.runInScope(ctx.correlationId(), ...)} by
     * {@link ScenarioExecutionService}, so every outbound HTTP call and Kafka publish this method (or
     * anything it calls) makes automatically carries the run's correlationId. Throwing means the
     * scenario harness itself failed (a service unreachable, a step impossible) — that is
     * {@code FAILED}, distinct from the scenario's *subject* failing as designed, which is a normal
     * return and {@code COMPLETED} (docs/openapi/scenario-service.yaml's ScenarioRunStatus).
     */
    void run(ScenarioRunContext ctx);
}

package com.orderfulfillment.scenario.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory bookkeeping for live runs, kept alongside (not instead of) the {@code scenario_runs}
 * table. Two things need to be real-time and cheap to check on every request:
 *
 * <ul>
 *   <li>{@code scenarioName -> runId} for a currently-{@code RUNNING} run — the 409 guard in
 *       docs/openapi/scenario-service.yaml ("two rapid calls to the same scenario name must actually
 *       conflict, not just usually happen to"). Guarded by a single {@link ConcurrentHashMap#putIfAbsent}
 *       so two concurrent requests for the same scenario name cannot both win.</li>
 *   <li>{@code correlationId -> runId} so {@code EventProjectionConsumer} can find the right run to
 *       append an EVENT timeline entry to as records are consumed, without a per-record database scan
 *       of {@code scenario_runs}.</li>
 * </ul>
 *
 * <p><b>Why the two mappings release on different schedules (issue #36):</b> a run's {@code RUNNING}
 * slot and its {@code correlationId -> runId} mapping used to be torn down together, the instant the
 * run reached a terminal status. That is correct for the slot (the 409 guard should free up the moment
 * the run is actually done) but wrong for the correlationId mapping: {@code EventProjectionConsumer}
 * runs in its own Kafka consumer group, entirely independent of whatever marks the run terminal (order
 * status via Order Service's own SSE stream, or — for {@code DuplicateEventScenario} — a fire-and-forget
 * republish sent moments before the run returns). That consumer group routinely has not caught up by
 * the time the run's harness thread returns, so a same-instant removal meant its own EVENT-kind
 * timeline entry silently had no run left to attach to. See {@link
 * com.orderfulfillment.scenario.scenarios.ScenarioRunExecutor#complete} for where the correlationId
 * mapping's removal is actually deferred, via {@link #retireCorrelation}, rather than done here.
 */
@Component
public class RunRegistry {

    private final Map<String, String> runningByScenario = new ConcurrentHashMap<>();
    private final Map<UUID, String> runIdByCorrelationId = new ConcurrentHashMap<>();

    /** Returns true if this call claimed the slot (no other run of this scenario was RUNNING). */
    public boolean tryStart(String scenarioName, String runId, UUID correlationId) {
        String existing = runningByScenario.putIfAbsent(scenarioName, runId);
        if (existing != null) {
            return false;
        }
        runIdByCorrelationId.put(correlationId, runId);
        return true;
    }

    public Optional<String> runningRunId(String scenarioName) {
        return Optional.ofNullable(runningByScenario.get(scenarioName));
    }

    public Optional<String> runIdForCorrelation(UUID correlationId) {
        return Optional.ofNullable(runIdByCorrelationId.get(correlationId));
    }

    /**
     * Full, immediate teardown of both mappings. Only correct to call when no downstream consumer
     * could possibly still be relying on the correlationId mapping — i.e. the run never actually
     * started ({@link com.orderfulfillment.scenario.scenarios.ScenarioExecutionService}'s rollback
     * when persisting the new run row fails). A run that did start must go through {@link
     * #releaseSlot} + {@link #retireCorrelation} instead, so the correlationId mapping outlives the
     * run by a grace period (issue #36).
     */
    public void finish(String scenarioName, UUID correlationId) {
        runningByScenario.remove(scenarioName);
        runIdByCorrelationId.remove(correlationId);
    }

    /** Frees the {@code scenarioName -> runId} slot (the 409 "already running" guard) as soon as a
     * run reaches a terminal status, so the same scenario can be started again immediately. Does not
     * touch the correlationId mapping — see {@link #retireCorrelation}. */
    public void releaseSlot(String scenarioName) {
        runningByScenario.remove(scenarioName);
    }

    /** Removes the correlationId -> runId mapping. Called once — after a grace period, not at the
     * instant the run completes — by {@code ScenarioRunExecutor}; see this class's Javadoc for why. */
    public void retireCorrelation(UUID correlationId) {
        runIdByCorrelationId.remove(correlationId);
    }

    public boolean anyRunning() {
        return !runningByScenario.isEmpty();
    }
}

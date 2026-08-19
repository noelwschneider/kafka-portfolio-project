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

    public void finish(String scenarioName, UUID correlationId) {
        runningByScenario.remove(scenarioName);
        runIdByCorrelationId.remove(correlationId);
    }

    public boolean anyRunning() {
        return !runningByScenario.isEmpty();
    }
}

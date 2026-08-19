package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.ConflictException;
import com.orderfulfillment.common.NotFoundException;
import com.orderfulfillment.scenario.catalog.ScenarioCatalog;
import com.orderfulfillment.scenario.catalog.ScenarioDefinitionSpec;
import com.orderfulfillment.scenario.domain.ScenarioRunEntity;
import com.orderfulfillment.scenario.domain.ScenarioRunRepository;
import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.runtime.RunRegistry;
import com.orderfulfillment.scenario.runtime.ScenarioRunMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatches {@code POST /demo/scenarios/{scenarioName}} to the matching {@link ScenarioRunner},
 * persists the new {@code RUNNING} row transactionally, and only then hands off to
 * {@link ScenarioRunExecutor} for the actual background run — so the async task never starts before
 * the row it will update has committed.
 */
@Service
public class ScenarioExecutionService {

    private final ScenarioCatalog catalog;
    private final Map<String, ScenarioRunner> runnersByName;
    private final ScenarioRunRepository runRepository;
    private final RunRegistry runRegistry;
    private final ScenarioRunMapper mapper;
    private final RunIdGenerator runIdGenerator;
    private final ScenarioRunExecutor executor;

    public ScenarioExecutionService(ScenarioCatalog catalog, List<ScenarioRunner> runners,
                                     ScenarioRunRepository runRepository, RunRegistry runRegistry,
                                     ScenarioRunMapper mapper, RunIdGenerator runIdGenerator,
                                     ScenarioRunExecutor executor) {
        this.catalog = catalog;
        this.runnersByName = runners.stream().collect(Collectors.toMap(ScenarioRunner::scenarioName, r -> r));
        this.runRepository = runRepository;
        this.runRegistry = runRegistry;
        this.mapper = mapper;
        this.runIdGenerator = runIdGenerator;
        this.executor = executor;
    }

    public ScenarioRunDto start(String scenarioName) {
        ScenarioDefinitionSpec spec = catalog.find(scenarioName)
                .orElseThrow(() -> new NotFoundException("SCENARIO_NOT_FOUND", "No scenario named " + scenarioName));
        if (!spec.available()) {
            throw new ConflictException("SCENARIO_UNAVAILABLE",
                    "Scenario " + scenarioName + " is not available in this build");
        }
        ScenarioRunner runner = runnersByName.get(scenarioName);
        if (runner == null) {
            throw new ConflictException("SCENARIO_UNAVAILABLE",
                    "Scenario " + scenarioName + " has no runner registered");
        }

        String runId = runIdGenerator.next();
        UUID correlationId = UUID.randomUUID();

        if (!runRegistry.tryStart(scenarioName, runId, correlationId)) {
            String runningId = runRegistry.runningRunId(scenarioName).orElse("unknown");
            throw new ConflictException("SCENARIO_ALREADY_RUNNING",
                    "Scenario " + scenarioName + " is already running as " + runningId);
        }

        try {
            ScenarioRunEntity entity = persist(runId, scenarioName, correlationId);
            executor.executeAsync(runner, runId, scenarioName, correlationId);
            return mapper.toDto(entity, List.of());
        } catch (RuntimeException e) {
            runRegistry.finish(scenarioName, correlationId);
            throw e;
        }
    }

    @Transactional
    ScenarioRunEntity persist(String runId, String scenarioName, UUID correlationId) {
        ScenarioRunEntity entity = new ScenarioRunEntity(runId, scenarioName, correlationId, Instant.now());
        return runRepository.save(entity);
    }
}

package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.scenario.domain.ScenarioRunEntity;
import com.orderfulfillment.scenario.domain.ScenarioRunRepository;
import com.orderfulfillment.scenario.domain.ScenarioRunStatus;
import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.runtime.RunEventHub;
import com.orderfulfillment.scenario.runtime.RunRegistry;
import com.orderfulfillment.scenario.runtime.ScenarioRunMapper;
import com.orderfulfillment.scenario.runtime.TimelineRecorder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The actual background execution of one scenario run, on its own bean (so {@code @Async} goes
 * through a real Spring AOP proxy — self-invocation from {@link ScenarioExecutionService} would
 * silently run synchronously instead).
 */
@Component
public class ScenarioRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunExecutor.class);

    private final ScenarioRunRepository runRepository;
    private final RunRegistry runRegistry;
    private final RunEventHub runEventHub;
    private final TimelineRecorder timelineRecorder;
    private final ScenarioRunMapper mapper;

    public ScenarioRunExecutor(ScenarioRunRepository runRepository, RunRegistry runRegistry,
                                RunEventHub runEventHub, TimelineRecorder timelineRecorder,
                                ScenarioRunMapper mapper) {
        this.runRepository = runRepository;
        this.runRegistry = runRegistry;
        this.runEventHub = runEventHub;
        this.timelineRecorder = timelineRecorder;
        this.mapper = mapper;
    }

    @Async("scenarioExecutor")
    public void executeAsync(ScenarioRunner runner, String runId, String scenarioName, UUID correlationId) {
        ScenarioRunContext ctx =
                new ScenarioRunContext(runId, correlationId, orderId -> setPrimaryOrderId(runId, orderId));
        try {
            CorrelationIdHolder.runInScope(correlationId, () -> runner.run(ctx));
            complete(runId, scenarioName, correlationId, ScenarioRunStatus.COMPLETED, null);
        } catch (Exception e) {
            log.warn("Scenario run {} ({}) failed", runId, scenarioName, e);
            complete(runId, scenarioName, correlationId, ScenarioRunStatus.FAILED, e.getMessage());
        }
    }

    @Transactional
    void setPrimaryOrderId(String runId, String orderId) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setOrderId(orderId);
            runRepository.save(run);
        });
    }

    @Transactional
    void complete(String runId, String scenarioName, UUID correlationId, ScenarioRunStatus status, String errorMessage) {
        ScenarioRunEntity run = runRepository.findById(runId).orElseThrow();
        run.setStatus(status);
        run.setCompletedAt(Instant.now());
        run.setErrorMessage(errorMessage);
        runRepository.save(run);

        ScenarioRunDto dto = mapper.toDto(run, List.of());
        runEventHub.publishRunStatus(runId, dto);
        runEventHub.close(runId);
        timelineRecorder.forget(runId);
        runRegistry.finish(scenarioName, correlationId);
    }
}

package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.scenario.config.ScenarioProperties;
import com.orderfulfillment.scenario.domain.ScenarioRunEntity;
import com.orderfulfillment.scenario.domain.ScenarioRunRepository;
import com.orderfulfillment.scenario.domain.ScenarioRunStatus;
import com.orderfulfillment.scenario.dto.ScenarioRunDto;
import com.orderfulfillment.scenario.runtime.RunEventHub;
import com.orderfulfillment.scenario.runtime.RunRegistry;
import com.orderfulfillment.scenario.runtime.ScenarioRunMapper;
import com.orderfulfillment.scenario.runtime.TimelineRecorder;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ScenarioProperties properties;

    /** Backs the deferred correlationId/timeline-sequence cleanup issue #36 requires (see {@link
     * #complete}) — a single daemon thread is plenty for the handful of scenario runs this service
     * ever has in flight at once. */
    private final ScheduledExecutorService lateCleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "scenario-run-late-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    public ScenarioRunExecutor(ScenarioRunRepository runRepository, RunRegistry runRegistry,
                                RunEventHub runEventHub, TimelineRecorder timelineRecorder,
                                ScenarioRunMapper mapper, ScenarioProperties properties) {
        this.runRepository = runRepository;
        this.runRegistry = runRegistry;
        this.runEventHub = runEventHub;
        this.timelineRecorder = timelineRecorder;
        this.mapper = mapper;
        this.properties = properties;
    }

    @PreDestroy
    void shutdownLateCleanupScheduler() {
        lateCleanupScheduler.shutdown();
    }

    @Async("scenarioExecutor")
    public void executeAsync(ScenarioRunner runner, String runId, String scenarioName, UUID correlationId) {
        ScenarioRunContext ctx =
                new ScenarioRunContext(runId, correlationId, orderId -> setPrimaryOrderId(runId, orderId));
        try {
            CorrelationIdHolder.runInScope(correlationId, () -> {
                // Phase 9: this is where a scenario's correlationId is minted — logged here, inside
                // the scope, so it's the first line of the trace a human would grep for across all
                // 5 services' logs.
                log.info("Starting scenario run {} ({})", runId, scenarioName);
                runner.run(ctx);
            });
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
        // The 409 "already running" guard frees immediately — a user retriggering the same scenario
        // right after it finishes must not see a stale conflict.
        runRegistry.releaseSlot(scenarioName);
        // The correlationId -> runId mapping (and TimelineRecorder's per-run sequence counter) stay
        // alive a while longer: issue #36. EventProjectionConsumer's own consumer group routinely
        // hasn't caught up on the run's last Kafka record(s) yet at this exact instant, and tearing
        // this bookkeeping down immediately meant that late EVENT-kind entry had nowhere to attach.
        scheduleLateCleanup(runId, correlationId);
    }

    private void scheduleLateCleanup(String runId, UUID correlationId) {
        try {
            lateCleanupScheduler.schedule(() -> {
                timelineRecorder.forget(runId);
                runRegistry.retireCorrelation(correlationId);
            }, properties.lateEventGraceMs(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Scheduler already shut down (application shutting down) — clean up inline rather than
            // leaking the mapping for the remainder of the process's life, which is now moot anyway.
            timelineRecorder.forget(runId);
            runRegistry.retireCorrelation(correlationId);
        }
    }
}

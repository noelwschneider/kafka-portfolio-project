package com.orderfulfillment.scenario.admin;

import com.orderfulfillment.common.ConflictException;
import com.orderfulfillment.scenario.config.IdleResetProperties;
import com.orderfulfillment.scenario.domain.ScenarioRunEntity;
import com.orderfulfillment.scenario.domain.ScenarioRunRepository;
import com.orderfulfillment.scenario.domain.ScenarioRunStatus;
import com.orderfulfillment.scenario.runtime.RunRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sprint 2 goal 4, W4 (docs/agent-reports/sprint-2/deployment-code-changes-briefing.md): a
 * scheduled job that recovers a demo box left in a stuck state after a scenario dies mid-run — a
 * paused Inventory/Fulfillment consumer or a Payment {@code REJECT} override with nothing left to
 * clear it (see {@link DemoResetService}'s Javadoc for what a full reset restores).
 *
 * <p><b>Off by default.</b> {@link IdleResetProperties#enabled()} gates the whole bean
 * ({@code @ConditionalOnProperty}), and only {@code application-production.yml} flips it on — an
 * idle reset firing during local development, where a developer might legitimately leave a scenario
 * run's inventory/payment state sitting around while debugging, would be actively confusing.
 *
 * <p><b>Single-replica assumption.</b> {@link RunRegistry} is per-JVM in-memory (see its own
 * Javadoc), so {@link RunRegistry#anyRunning()} only reflects runs started on <em>this</em> instance.
 * That's fine because Scenario Service runs a single replica today
 * (infrastructure/kubernetes/08-scenario-service.yaml: {@code replicas: 1}) — if that ever changes,
 * this guard needs revisiting (e.g. moving the "is anything running" check fully into the database),
 * not just this comment updating.
 *
 * <p>Reuses {@link DemoResetService#reset()} rather than reimplementing any of its recovery steps.
 * Its 409 guard ({@link ConflictException}) is treated as an expected, benign race — some other
 * caller (a real user starting a scenario between this job's own guard check and the
 * {@code reset()} call) won the moment — not as an error worth logging.
 */
@Component
@ConditionalOnProperty(prefix = "orderfulfillment.idle-reset", name = "enabled", havingValue = "true")
public class IdleResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdleResetScheduler.class);

    private final RunRegistry runRegistry;
    private final ScenarioRunRepository runRepository;
    private final DemoResetService demoResetService;
    private final IdleResetProperties properties;

    /** Idle-timer floor for a JVM that has never seen a scenario run: counts from process start,
     * not from epoch, so a freshly deployed instance doesn't fire an unnecessary reset on its very
     * first check. */
    private final Instant startedAt = Instant.now();

    /**
     * When this scheduler last actually ran {@link DemoResetService#reset()}. Without this, {@link
     * #lastActivityAt()} only ever consults {@code scenario_runs} — which an idle auto-reset does not
     * write to — so once the idle threshold was crossed once it stayed crossed forever and every
     * subsequent check fired another full reset (observed live: refired every 60s indefinitely, see
     * docs/agent-reports/sprint-2/deployment-execution-report.md §6 finding 3). Recording the
     * scheduler's own run as activity makes the idle clock restart the same way a real scenario run
     * restarts it.
     */
    private volatile Instant lastAutoResetAt;

    public IdleResetScheduler(RunRegistry runRegistry, ScenarioRunRepository runRepository,
                               DemoResetService demoResetService, IdleResetProperties properties) {
        this.runRegistry = runRegistry;
        this.runRepository = runRepository;
        this.demoResetService = demoResetService;
        this.properties = properties;
        log.info("Idle auto-reset enabled: idlePeriodMs={} checkIntervalMs={}",
                properties.idlePeriodMs(), properties.checkIntervalMs());
    }

    @Scheduled(fixedDelayString = "${orderfulfillment.idle-reset.check-interval-ms:60000}")
    public void checkIdleAndReset() {
        if (runRegistry.anyRunning() || runRepository.existsByStatus(ScenarioRunStatus.RUNNING)) {
            return;
        }

        Duration idleFor = Duration.between(lastActivityAt(), Instant.now());
        if (idleFor.toMillis() < properties.idlePeriodMs()) {
            return;
        }

        try {
            demoResetService.reset();
            lastAutoResetAt = Instant.now();
            log.info("Idle auto-reset triggered after {} of no scenario activity", idleFor);
        } catch (ConflictException e) {
            // Benign race: a run started between the guard check above and reset() itself. No-op,
            // not an error — the next check picks up fresh idle-time bookkeeping either way. Do not
            // update lastAutoResetAt here — no reset actually happened, so the idle clock must not
            // restart.
        }
    }

    private Instant lastActivityAt() {
        Instant lastRunActivity = runRepository.findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "startedAt")))
                .stream()
                .findFirst()
                .map(IdleResetScheduler::lastTimestamp)
                .orElse(startedAt);
        return lastAutoResetAt != null && lastAutoResetAt.isAfter(lastRunActivity) ? lastAutoResetAt : lastRunActivity;
    }

    private static Instant lastTimestamp(ScenarioRunEntity run) {
        Instant completedAt = run.getCompletedAt();
        return completedAt != null && completedAt.isAfter(run.getStartedAt()) ? completedAt : run.getStartedAt();
    }
}

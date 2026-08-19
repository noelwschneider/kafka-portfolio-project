package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.scenario.domain.ScenarioRunRepository;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Generates the short human-readable run ids, e.g. {@code "run-193"} (docs/db-ownership.md). Seeded
 * from the highest existing numeric suffix already in {@code scenario_runs} at startup, rather than
 * always starting at a fixed constant: this table's rows are deliberately kept across restarts (the
 * {@code POST /demo/reset} history-retention decision — see {@code DemoResetService}), so a counter
 * that always restarted at the same value would eventually collide with a still-present historical
 * row's primary key after any service restart. Real bug, not just a test artifact — surfaced by this
 * service's own integration suite recreating the Spring context (and hence this bean) between test
 * classes while sharing one Testcontainers Postgres underneath.
 */
@Component
public class RunIdGenerator {

    private static final String PREFIX = "run-";

    private final ScenarioRunRepository runRepository;
    private final AtomicLong sequence = new AtomicLong(100);

    public RunIdGenerator(ScenarioRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @PostConstruct
    void seedFromExistingRuns() {
        long highest = runRepository.findAll().stream()
                .map(run -> run.getId())
                .filter(id -> id != null && id.startsWith(PREFIX))
                .map(id -> id.substring(PREFIX.length()))
                .mapToLong(suffix -> parseOrZero(suffix))
                .max()
                .orElse(100L);
        sequence.set(Math.max(100L, highest));
    }

    public String next() {
        return PREFIX + sequence.incrementAndGet();
    }

    private static long parseOrZero(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

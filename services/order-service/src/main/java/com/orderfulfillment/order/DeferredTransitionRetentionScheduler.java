package com.orderfulfillment.order;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sprint 2 goal 2, item 4: retention for {@code deferred_transitions}
 * (docs/adr/ADR-009-out-of-order-status-transitions.md), which that ADR's "Accepted costs" section
 * flags as "one more thing to inspect" with no stated cleanup — like {@code processed_events}
 * (ADR-005), it grows without bound if nothing ever prunes it.
 *
 * <p>Only resolved rows are ever eligible: {@code APPLIED} once the transition table let the
 * transition through, {@code ABANDONED} once the order reached a terminal state it can never
 * follow (see {@code OrderPersistence#drainDeferred}). A {@code PENDING} row is a live parked
 * transition some order is still waiting on — deleting one would silently erase it rather than
 * resolve it, which is a correctness bug, not housekeeping, so this purge never touches one
 * regardless of age (see {@link DeferredTransitionRepository#deleteByStatusNotAndResolvedAtBefore}).
 *
 * <p>Same default retention window as {@code ProcessedEventRetentionScheduler}'s (7 days, matching
 * Kafka's own default topic retention) and the same {@code @Scheduled} shape as
 * {@code com.orderfulfillment.scenario.admin.IdleResetScheduler} — a daily tick, logged only when it
 * actually deletes something.
 */
@Component
class DeferredTransitionRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeferredTransitionRetentionScheduler.class);

    private final DeferredTransitionRepository repository;
    private final Duration retention;

    DeferredTransitionRetentionScheduler(DeferredTransitionRepository repository,
            @Value("${orderfulfillment.retention.deferred-transitions-days:7}") long retentionDays) {
        this.repository = repository;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(fixedDelayString = "${orderfulfillment.retention.check-interval-ms:86400000}")
    @Transactional
    void purgeResolved() {
        try {
            Instant cutoff = Instant.now().minus(retention);
            long deleted = repository.deleteByStatusNotAndResolvedAtBefore(DeferredTransitionStatus.PENDING, cutoff);
            if (deleted > 0) {
                log.info("Purged {} resolved deferred_transitions row(s) older than {}", deleted, retention);
            }
        } catch (Exception ex) {
            log.warn("Retention purge of deferred_transitions failed; retrying on the next tick", ex);
        }
    }
}

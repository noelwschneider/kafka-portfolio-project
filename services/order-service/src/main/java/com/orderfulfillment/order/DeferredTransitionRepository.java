package com.orderfulfillment.order;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeferredTransitionRepository extends JpaRepository<DeferredTransitionEntity, Long> {

    /**
     * Parked transitions for one order, oldest first. The caller always holds the order row's
     * pessimistic write lock (see {@link OrderPersistence}), so no separate lock is needed here.
     */
    List<DeferredTransitionEntity> findByOrderIdAndStatusOrderByIdAsc(String orderId, DeferredTransitionStatus status);

    /**
     * Sprint 2 goal 2, item 4: {@link DeferredTransitionRetentionScheduler}'s purge query. Only rows
     * that are no longer {@code status} (i.e. not {@code PENDING} — already {@code APPLIED} or
     * {@code ABANDONED}) and whose {@code resolved_at} is older than {@code cutoff} are eligible.
     * {@code PENDING} rows are never matched by this query regardless of age: one is a live parked
     * transition an order is still waiting on, and deleting it would silently make that transition
     * disappear rather than resolve it — worse than the unbounded growth this purge exists to fix.
     */
    long deleteByStatusNotAndResolvedAtBefore(DeferredTransitionStatus status, Instant cutoff);
}

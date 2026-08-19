package com.orderfulfillment.order;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    /**
     * The publisher's only query: pending rows in insertion order, so that events are sent in the
     * order their transactions committed them (ADR-006's ordering consequence — per-aggregate order
     * is what ADR-001's per-partition ordering relies on). {@code id} is a bigserial, so ascending
     * id is insertion order; {@code created_at} exists for the index and for the age-based FAILED
     * policy, not as the sort key (two rows can share a millisecond, ids cannot tie).
     *
     * <p>{@code PESSIMISTIC_WRITE} makes a second instance of this service block rather than send
     * the same rows concurrently — which would break ordering, not just duplicate. Ordering rules
     * out {@code SKIP LOCKED}, which is the throughput-oriented alternative and the right one for a
     * system that does not need per-aggregate order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OutboxEventEntity> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);
}

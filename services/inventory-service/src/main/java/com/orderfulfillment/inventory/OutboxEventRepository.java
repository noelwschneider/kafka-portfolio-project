package com.orderfulfillment.inventory;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    /**
     * The publisher's only query: pending rows in insertion order, matching Order Service's
     * {@code OutboxEventRepository} (ADR-006) — see that interface's Javadoc for why
     * {@code PESSIMISTIC_WRITE} and ascending {@code id} rather than {@code SKIP LOCKED}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OutboxEventEntity> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);
}

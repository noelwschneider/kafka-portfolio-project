package com.orderfulfillment.fulfillment;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

    /** See Order Service's {@code OutboxEventRepository} — the same ordering/locking rationale
     * applies here. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OutboxEventEntity> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);
}

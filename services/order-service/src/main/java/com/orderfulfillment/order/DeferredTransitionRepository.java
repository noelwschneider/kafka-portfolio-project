package com.orderfulfillment.order;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeferredTransitionRepository extends JpaRepository<DeferredTransitionEntity, Long> {

    /**
     * Parked transitions for one order, oldest first. The caller always holds the order row's
     * pessimistic write lock (see {@link OrderPersistence}), so no separate lock is needed here.
     */
    List<DeferredTransitionEntity> findByOrderIdAndStatusOrderByIdAsc(String orderId, DeferredTransitionStatus status);
}

package com.orderfulfillment.order;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    /**
     * {@code SELECT ... FOR UPDATE} on one order row. Every status transition takes this lock first
     * (ADR-009), which serializes the three independently-consumed topics that write
     * {@code orders.status} against each other for a given order — without it, "read current status,
     * decide, write" is a check-then-act race between two consumer threads (or two Order Service
     * replicas) and the guard could be evaluated against a status another transaction is about to
     * change. Per-order only: different orders never contend.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrderEntity o where o.id = :id")
    Optional<OrderEntity> findByIdForUpdate(@Param("id") String id);

    Page<OrderEntity> findByStatusAndCustomerIdOrderByCreatedAtDesc(OrderStatus status, String customerId, Pageable pageable);

    Page<OrderEntity> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    Page<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

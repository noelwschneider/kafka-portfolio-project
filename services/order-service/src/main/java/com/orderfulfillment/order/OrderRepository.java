package com.orderfulfillment.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    Page<OrderEntity> findByStatusAndCustomerIdOrderByCreatedAtDesc(OrderStatus status, String customerId, Pageable pageable);

    Page<OrderEntity> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    Page<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    Page<OrderEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

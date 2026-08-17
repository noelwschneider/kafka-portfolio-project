package com.orderfulfillment.monolith.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, String> {
    Optional<PaymentAttemptEntity> findByOrderId(String orderId);
}

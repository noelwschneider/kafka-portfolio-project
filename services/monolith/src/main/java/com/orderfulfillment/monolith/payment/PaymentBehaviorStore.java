package com.orderfulfillment.monolith.payment;

import com.orderfulfillment.monolith.payment.dto.PaymentBehaviorDto;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * In-memory override for the payment simulator, per docs/openapi/payment-service.yaml's
 * PUT /demo/payment-behavior. Held in memory and does not survive a restart, exactly as specified.
 */
@Component
public class PaymentBehaviorStore {

    private final AtomicReference<PaymentBehaviorDto> current =
            new AtomicReference<>(PaymentBehaviorDto.defaultSuccess());

    public PaymentBehaviorDto get() {
        return current.get();
    }

    public PaymentBehaviorDto set(PaymentBehaviorDto behavior) {
        current.set(behavior);
        return behavior;
    }

    public void clear() {
        current.set(PaymentBehaviorDto.defaultSuccess());
    }

    /** The behavior that applies to a specific order: an order-scoped override, else the global one. */
    public PaymentBehaviorDto resolveFor(String orderId) {
        PaymentBehaviorDto behavior = current.get();
        if (behavior.orderId() != null && !behavior.orderId().equals(orderId)) {
            return PaymentBehaviorDto.defaultSuccess();
        }
        return behavior;
    }
}

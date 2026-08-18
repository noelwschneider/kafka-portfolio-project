package com.orderfulfillment.payment;

import com.orderfulfillment.payment.dto.PaymentBehaviorDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/openapi/payment-service.yaml's /demo namespace — simulator control, never mixed into /api
 * (docs/planning/agent-guidance.md rule 9).
 */
@RestController
@RequestMapping("/demo/payment-behavior")
public class PaymentDemoController {

    private final PaymentBehaviorStore behaviorStore;

    public PaymentDemoController(PaymentBehaviorStore behaviorStore) {
        this.behaviorStore = behaviorStore;
    }

    @GetMapping
    public PaymentBehaviorDto getPaymentBehavior() {
        return behaviorStore.get();
    }

    @PutMapping
    public PaymentBehaviorDto setPaymentBehavior(@Valid @RequestBody PaymentBehaviorDto behavior) {
        return behaviorStore.set(behavior);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearPaymentBehavior() {
        behaviorStore.clear();
    }
}

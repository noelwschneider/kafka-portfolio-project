package com.orderfulfillment.monolith.payment;

import com.orderfulfillment.monolith.payment.dto.PaymentAttemptDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/payment-service.yaml's /api namespace — read-only view of simulated attempts. */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/{orderId}")
    public PaymentAttemptDto getPaymentByOrderId(@PathVariable String orderId) {
        return paymentService.getByOrderId(orderId);
    }
}

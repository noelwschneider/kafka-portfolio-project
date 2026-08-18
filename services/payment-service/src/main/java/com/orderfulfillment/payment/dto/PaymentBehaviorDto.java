package com.orderfulfillment.payment.dto;

import jakarta.validation.constraints.NotNull;

public record PaymentBehaviorDto(
        @NotNull PaymentBehaviorMode mode,
        String orderId,
        String failureReason
) {
    public static PaymentBehaviorDto defaultSuccess() {
        return new PaymentBehaviorDto(PaymentBehaviorMode.DEFAULT_SUCCESS, null, null);
    }
}

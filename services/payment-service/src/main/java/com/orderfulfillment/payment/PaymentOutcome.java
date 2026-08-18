package com.orderfulfillment.payment;

/**
 * Result of {@link PaymentService#authorize}, standing in for the PaymentAuthorized /
 * PaymentRejected event pair (docs/events/event-catalog.md).
 */
public record PaymentOutcome(Kind kind, String paymentAttemptId, PaymentFailureReason failureReason) {

    public enum Kind {
        AUTHORIZED,
        REJECTED,
        /**
         * Simulated transient provider error. Phase 2+ retries with backoff and routes to
         * payments.dlq if attempts are exhausted, leaving the order in PAYMENT_PENDING. This
         * phase has no retry/DLQ machinery yet, so OrderService treats it as the FAILED terminal
         * transition instead (docs/order-state-machine.md transition 9) — a documented, temporary
         * simplification, not a claim that retries happened.
         */
        PROVIDER_ERROR,
        /**
         * An earlier (or concurrent) delivery of the same Kafka event already claimed the
         * {@code processed_events} ledger row and ran this authorization
         * (docs/reliability-pattern.md §2.4). Distinct from a real business outcome so the consumer
         * publishes nothing for it — the earlier delivery already published the real answer.
         */
        DUPLICATE
    }

    public static PaymentOutcome authorized(String paymentAttemptId) {
        return new PaymentOutcome(Kind.AUTHORIZED, paymentAttemptId, null);
    }

    public static PaymentOutcome rejected(String paymentAttemptId, PaymentFailureReason reason) {
        return new PaymentOutcome(Kind.REJECTED, paymentAttemptId, reason);
    }

    public static PaymentOutcome providerError(String paymentAttemptId) {
        return new PaymentOutcome(Kind.PROVIDER_ERROR, paymentAttemptId, null);
    }

    public static PaymentOutcome duplicate() {
        return new PaymentOutcome(Kind.DUPLICATE, null, null);
    }
}

package com.orderfulfillment.payment;

/**
 * docs/events/event-catalog.md §3 (PaymentRejected note): a simulated retryable provider error
 * "raises inside the Payment Service consumer, is retried with backoff, and lands in
 * payments.dlq if attempts are exhausted... It never surfaces as PaymentRejected, and it leaves
 * the order in PAYMENT_PENDING." No retry/backoff/DLQ machinery exists yet (Phase 4), so this
 * phase's honest behavior is to let the consumer fail loudly (this exception, uncaught) rather
 * than fabricate a retry loop or silently swallow it — per this phase's "no idempotency, no
 * outbox, no DLQ yet" rule ("If a consumer receives a message it can't process, letting it fail
 * loudly ... is fine for this phase"). The order is left in PAYMENT_PENDING, matching the
 * catalog's documented behavior for this case exactly (a deliberate change from Phase 1's
 * improvised mapping of this mode onto the FAILED terminal state — see docs/agent-reports/phase-2.md).
 */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String orderId, String paymentAttemptId) {
        super("Simulated retryable provider error for order " + orderId + " (attempt " + paymentAttemptId + ")");
    }
}

package com.orderfulfillment.payment;

import com.orderfulfillment.common.IdGenerator;
import com.orderfulfillment.common.NotFoundException;
import com.orderfulfillment.payment.dto.PaymentAttemptDto;
import com.orderfulfillment.payment.dto.PaymentBehaviorDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic payment simulator. Runs in-process this phase, standing in for Payment Service
 * consuming PaymentRequested (docs/events/event-catalog.md). No real provider, no card data, no
 * money moves (docs/planning/project-overview.md's Explicit Non-Goals section).
 */
@Service
public class PaymentService {

    private final PaymentAttemptRepository repository;
    private final PaymentBehaviorStore behaviorStore;
    private final IdGenerator idGenerator;

    public PaymentService(PaymentAttemptRepository repository, PaymentBehaviorStore behaviorStore, IdGenerator idGenerator) {
        this.repository = repository;
        this.behaviorStore = behaviorStore;
        this.idGenerator = idGenerator;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentOutcome authorize(String orderId, BigDecimal amount, UUID idempotencyKey) {
        PaymentBehaviorDto behavior = behaviorStore.resolveFor(orderId);
        String attemptId = idGenerator.nextPaymentId();
        Instant now = Instant.now();

        return switch (behavior.mode()) {
            case DEFAULT_SUCCESS -> {
                repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                        PaymentAttemptStatus.AUTHORIZED, amount, null, now));
                yield PaymentOutcome.authorized(attemptId);
            }
            case REJECT -> {
                PaymentFailureReason reason = behavior.failureReason() != null
                        ? PaymentFailureReason.valueOf(behavior.failureReason())
                        : PaymentFailureReason.CARD_DECLINED;
                repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                        PaymentAttemptStatus.REJECTED, amount, reason, now));
                yield PaymentOutcome.rejected(attemptId, reason);
            }
            case RETRYABLE_ERROR -> {
                repository.save(new PaymentAttemptEntity(attemptId, orderId, idempotencyKey,
                        PaymentAttemptStatus.PENDING, amount, null, now));
                yield PaymentOutcome.providerError(attemptId);
            }
        };
    }

    @Transactional(readOnly = true)
    public PaymentAttemptDto getByOrderId(String orderId) {
        PaymentAttemptEntity attempt = repository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "No payment attempt for order " + orderId));
        return toDto(attempt);
    }

    private PaymentAttemptDto toDto(PaymentAttemptEntity attempt) {
        return new PaymentAttemptDto(
                attempt.getId(),
                attempt.getOrderId(),
                attempt.getStatus().name(),
                attempt.getAmount(),
                attempt.getFailureReason() != null ? attempt.getFailureReason().name() : null,
                attempt.getIdempotencyKey(),
                attempt.getCreatedAt(),
                attempt.getUpdatedAt()
        );
    }
}

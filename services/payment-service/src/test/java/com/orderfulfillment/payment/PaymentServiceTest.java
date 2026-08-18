package com.orderfulfillment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderfulfillment.common.IdGenerator;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.payment.dto.PaymentBehaviorDto;
import com.orderfulfillment.payment.dto.PaymentBehaviorMode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

    private PaymentAttemptRepository repository;
    private PaymentBehaviorStore behaviorStore;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentAttemptRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        behaviorStore = new PaymentBehaviorStore();
        IdGenerator idGenerator = new IdGenerator();
        // These unit tests exercise the no-event-key overload, which never touches the ledger.
        ProcessedEventLedger processedEventLedger = mock(ProcessedEventLedger.class);
        paymentService = new PaymentService(repository, behaviorStore, idGenerator, processedEventLedger);
    }

    @Test
    void defaultBehaviorAuthorizesEveryRequest() {
        PaymentOutcome outcome = paymentService.authorize("order-1", new BigDecimal("129.00"), UUID.randomUUID());
        assertThat(outcome.kind()).isEqualTo(PaymentOutcome.Kind.AUTHORIZED);
    }

    @Test
    void globalRejectOverrideAppliesToAnyOrder() {
        behaviorStore.set(new PaymentBehaviorDto(PaymentBehaviorMode.REJECT, null, "CARD_DECLINED"));

        PaymentOutcome outcome = paymentService.authorize("order-99", new BigDecimal("50.00"), UUID.randomUUID());

        assertThat(outcome.kind()).isEqualTo(PaymentOutcome.Kind.REJECTED);
        assertThat(outcome.failureReason()).isEqualTo(PaymentFailureReason.CARD_DECLINED);
    }

    @Test
    void orderScopedOverrideDoesNotAffectOtherOrders() {
        behaviorStore.set(new PaymentBehaviorDto(PaymentBehaviorMode.REJECT, "order-42", "INSUFFICIENT_FUNDS"));

        PaymentOutcome targetOutcome = paymentService.authorize("order-42", new BigDecimal("50.00"), UUID.randomUUID());
        PaymentOutcome otherOutcome = paymentService.authorize("order-43", new BigDecimal("50.00"), UUID.randomUUID());

        assertThat(targetOutcome.kind()).isEqualTo(PaymentOutcome.Kind.REJECTED);
        assertThat(otherOutcome.kind()).isEqualTo(PaymentOutcome.Kind.AUTHORIZED);
    }

    @Test
    void retryableErrorModeReturnsProviderError() {
        behaviorStore.set(new PaymentBehaviorDto(PaymentBehaviorMode.RETRYABLE_ERROR, null, null));

        PaymentOutcome outcome = paymentService.authorize("order-1", new BigDecimal("50.00"), UUID.randomUUID());

        assertThat(outcome.kind()).isEqualTo(PaymentOutcome.Kind.PROVIDER_ERROR);
    }

    @Test
    void clearingBehaviorReturnsToDefaultSuccess() {
        behaviorStore.set(new PaymentBehaviorDto(PaymentBehaviorMode.REJECT, null, null));
        behaviorStore.clear();

        PaymentOutcome outcome = paymentService.authorize("order-1", new BigDecimal("50.00"), UUID.randomUUID());

        assertThat(outcome.kind()).isEqualTo(PaymentOutcome.Kind.AUTHORIZED);
    }
}

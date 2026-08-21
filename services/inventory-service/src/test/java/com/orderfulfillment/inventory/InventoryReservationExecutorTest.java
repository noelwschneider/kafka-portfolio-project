package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderfulfillment.common.IdGenerator;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InventoryReservationExecutorTest {

    @Mock
    private InventoryItemRepository itemRepository;
    @Mock
    private InventoryReservationRepository reservationRepository;
    @Mock
    private IdGenerator idGenerator;
    /** Never consulted here: every call in this class passes a null event key, i.e. "not driven by
     * a Kafka record, nothing to deduplicate". The ledger's real behaviour is covered against a
     * real database by {@link InventoryDuplicateEventIntegrationTest}. */
    @Mock
    private ProcessedEventLedger processedEventLedger;
    /** Never consulted here either, for the same reason: a null event key means nothing is recorded
     * to the outbox (see InventoryReservationExecutor's Sprint 2 outbox calls). */
    @Mock
    private OutboxRecorder outboxRecorder;

    private InventoryReservationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new InventoryReservationExecutor(
                itemRepository, reservationRepository, idGenerator, processedEventLedger, outboxRecorder);
    }

    @Test
    void reservesAllLinesWhenStockIsSufficient() {
        InventoryItemEntity item = new InventoryItemEntity("SKU-001", "Mechanical Keyboard", 10, 0, Instant.now());
        when(itemRepository.findById("SKU-001")).thenReturn(Optional.of(item));
        when(idGenerator.nextReservationId()).thenReturn("resv-1");

        ReservationResult result = executor.attemptReserve("order-1", List.of(new OrderLine("SKU-001", 2)), null);

        assertThat(result.success()).isTrue();
        assertThat(item.getReservedQuantity()).isEqualTo(2);
        verify(reservationRepository).save(any(InventoryReservationEntity.class));
    }

    @Test
    void failsAllOrNothingWhenOneLineIsShort() {
        InventoryItemEntity plenty = new InventoryItemEntity("SKU-001", "Mechanical Keyboard", 10, 0, Instant.now());
        InventoryItemEntity scarce = new InventoryItemEntity("SKU-004", "External SSD", 2, 0, Instant.now());
        when(itemRepository.findById("SKU-001")).thenReturn(Optional.of(plenty));
        when(itemRepository.findById("SKU-004")).thenReturn(Optional.of(scarce));

        ReservationResult result = executor.attemptReserve("order-2",
                List.of(new OrderLine("SKU-001", 1), new OrderLine("SKU-004", 5)), null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(result.shortages()).containsExactly(new Shortage("SKU-004", 5, 2));
        // all-or-nothing: the sufficient line must not have been reserved either
        assertThat(plenty.getReservedQuantity()).isZero();
    }

    @Test
    void unknownSkuIsReportedAsShortage() {
        when(itemRepository.findById("SKU-999")).thenReturn(Optional.empty());

        ReservationResult result = executor.attemptReserve("order-3", List.of(new OrderLine("SKU-999", 1)), null);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("UNKNOWN_SKU");
    }

    @Test
    void releaseDecrementsReservedQuantityAndMarksReservationReleased() {
        InventoryItemEntity item = new InventoryItemEntity("SKU-001", "Mechanical Keyboard", 10, 2, Instant.now());
        InventoryReservationEntity reservation = new InventoryReservationEntity(
                "resv-1-SKU-001", "order-1", "SKU-001", 2, ReservationStatus.RESERVED, Instant.now());
        when(reservationRepository.findByOrderIdAndStatus("order-1", ReservationStatus.RESERVED))
                .thenReturn(List.of(reservation));
        when(itemRepository.findById("SKU-001")).thenReturn(Optional.of(item));

        ReleaseResult result = executor.release("order-1", null);

        assertThat(item.getReservedQuantity()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(result.reservationId()).isEqualTo("resv-1");
        assertThat(result.items()).containsExactly(new OrderLine("SKU-001", 2));
    }

    @Test
    void releaseOfAnOrderWithNoReservationsReturnsNone() {
        when(reservationRepository.findByOrderIdAndStatus("order-none", ReservationStatus.RESERVED))
                .thenReturn(List.of());

        ReleaseResult result = executor.release("order-none", null);

        assertThat(result).isEqualTo(ReleaseResult.NONE);
    }
}

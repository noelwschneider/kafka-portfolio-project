package com.orderfulfillment.monolith.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orderfulfillment.monolith.common.IdGenerator;
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

    private InventoryReservationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new InventoryReservationExecutor(itemRepository, reservationRepository, idGenerator);
    }

    @Test
    void reservesAllLinesWhenStockIsSufficient() {
        InventoryItemEntity item = new InventoryItemEntity("SKU-001", "Mechanical Keyboard", 10, 0, Instant.now());
        when(itemRepository.findById("SKU-001")).thenReturn(Optional.of(item));
        when(idGenerator.nextReservationId()).thenReturn("resv-1");

        ReservationResult result = executor.attemptReserve("order-1", List.of(new OrderLine("SKU-001", 2)));

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
                List.of(new OrderLine("SKU-001", 1), new OrderLine("SKU-004", 5)));

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(result.shortages()).containsExactly(new Shortage("SKU-004", 5, 2));
        // all-or-nothing: the sufficient line must not have been reserved either
        assertThat(plenty.getReservedQuantity()).isZero();
    }

    @Test
    void unknownSkuIsReportedAsShortage() {
        when(itemRepository.findById("SKU-999")).thenReturn(Optional.empty());

        ReservationResult result = executor.attemptReserve("order-3", List.of(new OrderLine("SKU-999", 1)));

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

        executor.release("order-1");

        assertThat(item.getReservedQuantity()).isZero();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }
}

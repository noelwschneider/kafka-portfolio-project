package com.orderfulfillment.monolith.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Exercises InventoryService's retry-on-version-conflict behavior (docs/db-ownership.md's
 * inventory_items.version column) without needing real concurrent load — Scenario 7's actual
 * concurrency test is Phase 4's job (docs/planning/execution-plan.md §2), but the version column
 * must behave correctly on a conflict now, per this phase's exit criteria.
 */
class InventoryServiceOptimisticLockTest {

    @Test
    void retriesOnOptimisticLockConflictAndSucceeds() {
        InventoryItemRepository itemRepository = mock(InventoryItemRepository.class);
        InventoryReservationExecutor executor = mock(InventoryReservationExecutor.class);
        InventoryService service = new InventoryService(itemRepository, executor);

        List<OrderLine> lines = List.of(new OrderLine("SKU-001", 1));
        when(executor.attemptReserve("order-1", lines))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryItemEntity.class, "SKU-001"))
                .thenReturn(ReservationResult.reserved("resv-1"));

        ReservationResult result = service.reserve("order-1", lines);

        assertThat(result.success()).isTrue();
        verify(executor, times(2)).attemptReserve("order-1", lines);
    }

    @Test
    void givesUpAfterMaxRetriesAndPropagatesConflict() {
        InventoryItemRepository itemRepository = mock(InventoryItemRepository.class);
        InventoryReservationExecutor executor = mock(InventoryReservationExecutor.class);
        InventoryService service = new InventoryService(itemRepository, executor);

        List<OrderLine> lines = List.of(new OrderLine("SKU-001", 1));
        when(executor.attemptReserve("order-1", lines))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryItemEntity.class, "SKU-001"));

        assertThatThrownBy(() -> service.reserve("order-1", lines))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(executor, times(3)).attemptReserve("order-1", lines);
    }
}

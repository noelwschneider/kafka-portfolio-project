package com.orderfulfillment.inventory;

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
 * Exercises InventoryService's retry-on-version-conflict <em>wrapper logic</em>
 * (docs/db-ownership.md's inventory_items.version column) against a mocked executor.
 *
 * <p><b>Scope warning — read before trusting this file.</b> Everything here is driven by a Mockito
 * stub told when to throw. It proves the loop counts and rethrows correctly; it proves nothing
 * about whether Postgres and Hibernate actually produce that exception under real concurrent
 * writes, nor whether the retry budget is large enough for real contention. It did not, and could
 * not, catch the retry-exhaustion bug described in
 * docs/agent-reports/phase-3-inventory-concurrency.md — a stub that throws exactly twice will
 * always pass a loop with any budget above two. {@link InventoryConcurrencyIntegrationTest} is the
 * test that actually proves the invariant, against real concurrent transactions.
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
        // 25 = InventoryService.MAX_OPTIMISTIC_LOCK_ATTEMPTS. A stub that conflicts forever is the
        // one thing real contention cannot produce (every real conflict consumes stock and so makes
        // progress) — this only pins down that the loop is bounded and rethrows the last conflict.
        verify(executor, times(25)).attemptReserve("order-1", lines);
    }
}

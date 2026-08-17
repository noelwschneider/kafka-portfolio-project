package com.orderfulfillment.monolith.inventory;

import com.orderfulfillment.monolith.common.ConflictException;
import com.orderfulfillment.monolith.common.NotFoundException;
import com.orderfulfillment.monolith.inventory.dto.InventoryItemDto;
import java.time.Instant;
import java.util.List;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation logic runs in-process this phase, standing in for Inventory Service consuming
 * OrderCreated / PaymentRejected (docs/events/event-catalog.md). Kept as its own @Transactional
 * boundary per call (via InventoryReservationExecutor), rather than sharing OrderService's
 * transaction, so this reads the way it will once Phase 3 extracts it into a separate service with
 * its own database connection.
 */
@Service
public class InventoryService {

    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;

    private final InventoryItemRepository itemRepository;
    private final InventoryReservationExecutor executor;

    public InventoryService(InventoryItemRepository itemRepository, InventoryReservationExecutor executor) {
        this.itemRepository = itemRepository;
        this.executor = executor;
    }

    @Transactional(readOnly = true)
    public List<InventoryItemDto> listAll() {
        return itemRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public InventoryItemDto getBySku(String sku) {
        return toDto(findItem(sku));
    }

    @Transactional
    public InventoryItemDto updateAvailableQuantity(String sku, int newAvailableQuantity) {
        InventoryItemEntity item = findItem(sku);
        if (newAvailableQuantity < item.getReservedQuantity()) {
            throw new ConflictException("INVENTORY_CONFLICT",
                    "availableQuantity %d is below the %d units currently reserved for %s"
                            .formatted(newAvailableQuantity, item.getReservedQuantity(), sku));
        }
        item.setAvailableQuantity(newAvailableQuantity);
        item.setUpdatedAt(Instant.now());
        return toDto(item);
    }

    /** All-or-nothing reservation for every line of one order. Retries on a version conflict. */
    public ReservationResult reserve(String orderId, List<OrderLine> lines) {
        ObjectOptimisticLockingFailureException lastConflict = null;
        for (int attempt = 0; attempt < MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            try {
                return executor.attemptReserve(orderId, lines);
            } catch (ObjectOptimisticLockingFailureException conflict) {
                lastConflict = conflict;
            }
        }
        throw lastConflict;
    }

    /** Releases every RESERVED reservation for an order — the InventoryReleased compensation step. */
    public void release(String orderId) {
        executor.release(orderId);
    }

    private InventoryItemEntity findItem(String sku) {
        return itemRepository.findById(sku)
                .orElseThrow(() -> new NotFoundException("SKU_NOT_FOUND", "No inventory item for " + sku));
    }

    private InventoryItemDto toDto(InventoryItemEntity item) {
        return new InventoryItemDto(item.getSku(), item.getDisplayName(), item.getAvailableQuantity(),
                item.getReservedQuantity(), item.getVersion(), item.getUpdatedAt());
    }
}

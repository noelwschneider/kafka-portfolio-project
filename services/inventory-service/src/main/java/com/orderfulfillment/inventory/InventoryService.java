package com.orderfulfillment.inventory;

import com.orderfulfillment.common.ConflictException;
import com.orderfulfillment.common.NotFoundException;
import com.orderfulfillment.inventory.dto.InventoryItemDto;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Called from {@link InventoryOrderEventsConsumer} / {@link InventoryPaymentEventsConsumer} —
 * the domain logic behind Inventory Service's reaction to OrderCreated / PaymentRejected
 * (docs/events/event-catalog.md). Kept as its own @Transactional boundary per call (via
 * InventoryReservationExecutor), independent of whatever transaction the calling Kafka listener is
 * in, so this reads the way it will once Phase 3 extracts it into a separate service with its own
 * database connection.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    /**
     * Attempts, not "retries after the first" — attempt 1 is the initial try. Sized to cover far
     * more competing commits than this system can produce (Kafka partitions x listener concurrency
     * x instances), so exhaustion means something pathological, not ordinary contention. See
     * {@link #reserve} for why a conflict always implies forward progress and the loop terminates.
     */
    private static final int MAX_OPTIMISTIC_LOCK_ATTEMPTS = 25;

    private static final long BASE_BACKOFF_NANOS = 200_000L;   // 0.2 ms
    private static final long MAX_BACKOFF_NANOS = 10_000_000L;  // 10 ms

    /**
     * Counts real {@code @Version} conflicts observed against the database. Exposed so
     * {@code InventoryConcurrencyIntegrationTest} can assert the conflict path was genuinely
     * exercised rather than assert an invariant that held only because nothing ever raced.
     */
    private final AtomicLong optimisticLockConflicts = new AtomicLong();

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

    /**
     * All-or-nothing reservation for every line of one order. Retries on a version conflict.
     *
     * <p><b>Why the retry budget is what it is.</b> A version conflict here is never a "maybe it
     * will work next time" retry: losing the compare-and-set on {@code inventory_items.version}
     * is <em>proof</em> that a competing transaction committed a change to that row. In the
     * reservation workload that competing commit consumed stock, so every conflict this loop
     * observes is global forward progress, and the loop is guaranteed to terminate — either this
     * order eventually wins the CAS, or it re-reads a row with too little free stock and returns a
     * clean {@code INSUFFICIENT_STOCK} result without writing at all. The bound therefore only has
     * to cover the number of competing commits that can occur while one order is trying, which is
     * bounded by the stock being contended for.
     *
     * <p>The previous bound of 3 attempts with no backoff was too small and was demonstrated to
     * fail: under genuinely simultaneous load an order could lose three CAS races in a row and this
     * method would then throw {@link ObjectOptimisticLockingFailureException} out of
     * {@link InventoryOrderEventsConsumer}'s {@code @KafkaListener}, publishing neither
     * InventoryReserved nor InventoryReservationFailed and leaving that order stranded in PENDING.
     * See {@code InventoryConcurrencyIntegrationTest} and
     * docs/agent-reports/phase-3-inventory-concurrency.md.
     *
     * <p>Backoff is randomized so that contenders that collided once do not re-collide in lockstep.
     */
    public ReservationResult reserve(String orderId, List<OrderLine> lines) {
        ObjectOptimisticLockingFailureException lastConflict = null;
        for (int attempt = 0; attempt < MAX_OPTIMISTIC_LOCK_ATTEMPTS; attempt++) {
            try {
                return executor.attemptReserve(orderId, lines);
            } catch (ObjectOptimisticLockingFailureException conflict) {
                lastConflict = conflict;
                optimisticLockConflicts.incrementAndGet();
                log.debug("Optimistic lock conflict reserving for order {} (attempt {}/{}); retrying",
                        orderId, attempt + 1, MAX_OPTIMISTIC_LOCK_ATTEMPTS);
                backOff(attempt);
            }
        }
        // Unreachable under any contention level this system can actually produce (see above): the
        // Kafka consumer path is bounded by partition count x listener concurrency x instances.
        // Logged at ERROR rather than swallowed because the caller has no contract-legal way to
        // report it — InventoryReservationFailed.reason is frozen to INSUFFICIENT_STOCK/UNKNOWN_SKU
        // (docs/events/event-catalog.md), neither of which is true here. Propagating lets Spring
        // Kafka's error handler redeliver the record, which is safe: the losing attempt's
        // transaction rolled back, so a redelivery re-reads fresh state and writes nothing twice.
        log.error("Gave up reserving for order {} after {} optimistic-lock conflicts; the OrderCreated "
                + "record will be redelivered by the consumer error handler",
                orderId, MAX_OPTIMISTIC_LOCK_ATTEMPTS);
        throw lastConflict;
    }

    /** Randomized, capped backoff between CAS retries. Jitter, not a fixed sleep, so that two
     * contenders that just collided do not line up again on the next attempt. */
    private void backOff(int attempt) {
        long ceilingNanos = Math.min(BASE_BACKOFF_NANOS << Math.min(attempt, 6), MAX_BACKOFF_NANOS);
        LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(ceilingNanos) + 1);
    }

    /** Number of real database version conflicts this instance has retried. Test/diagnostic only. */
    long optimisticLockConflictCount() {
        return optimisticLockConflicts.get();
    }

    /** Releases every RESERVED reservation for an order — the InventoryReleased compensation step. */
    public ReleaseResult release(String orderId) {
        return executor.release(orderId);
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

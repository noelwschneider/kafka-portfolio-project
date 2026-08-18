package com.orderfulfillment.inventory;

import com.orderfulfillment.common.IdGenerator;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Split out from InventoryService so its @Transactional(REQUIRES_NEW) methods go through Spring's
 * proxy — a self-invoked call (this.attemptReserve(...)) from within InventoryService would
 * silently skip the proxy and run without a transaction/retry boundary at all.
 *
 * <p>This class is also where the {@code processed_events} claim happens, and it has to be: ADR-005
 * requires the ledger row and the business change to commit in <em>one</em> local transaction, and
 * this method — not the listener, and not {@link InventoryService#reserve}'s retry loop around it —
 * is that transaction. Claiming the event one level up would leave the row in an outer transaction
 * that commits separately from the reservation it is supposed to be atomic with. Claiming it inside
 * also makes the retry loop behave correctly for free: an attempt that loses the optimistic-lock
 * race rolls back its ledger row along with everything else, so the next attempt starts clean.
 */
@Component
class InventoryReservationExecutor {

    private final InventoryItemRepository itemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final IdGenerator idGenerator;
    private final ProcessedEventLedger processedEventLedger;

    InventoryReservationExecutor(InventoryItemRepository itemRepository,
                                  InventoryReservationRepository reservationRepository,
                                  IdGenerator idGenerator,
                                  ProcessedEventLedger processedEventLedger) {
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.idGenerator = idGenerator;
        this.processedEventLedger = processedEventLedger;
    }

    /**
     * @param eventKey the event being applied, or {@code null} for a call that does not originate
     *                 from a Kafka record (administrative and test callers) and so has nothing to
     *                 deduplicate against
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationResult attemptReserve(String orderId, List<OrderLine> lines, ProcessedEventKey eventKey) {
        if (eventKey != null && !processedEventLedger.recordProcessed(eventKey)) {
            return ReservationResult.DUPLICATE;
        }

        // Lines are summed per SKU before anything is checked or written. An order carrying the
        // same SKU on two lines used to be checked line-by-line against the *unmutated* free
        // quantity, so 2 + 2 against a stock of 2 passed both checks and then applied both
        // increments — reserving 4 of 2. It also collapsed to a single reservation row (the row id
        // is derived from the SKU, and inventory_reservations is UNIQUE (order_id, sku)), so the
        // release path would have handed back only half of what was taken, leaking stock
        // permanently. Summing first makes the check and the write agree, and matches what the
        // frozen schema's UNIQUE (order_id, sku) already assumes: one row per (order, SKU).
        Map<String, Integer> requested = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            requested.merge(line.sku(), line.quantity(), Integer::sum);
        }

        List<Shortage> shortages = new ArrayList<>();
        boolean anyUnknownSku = false;
        Map<String, InventoryItemEntity> resolved = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            String sku = entry.getKey();
            int quantity = entry.getValue();
            InventoryItemEntity item = itemRepository.findById(sku).orElse(null);
            if (item == null) {
                shortages.add(new Shortage(sku, quantity, 0));
                anyUnknownSku = true;
                continue;
            }
            resolved.put(sku, item);
            if (item.freeQuantity() < quantity) {
                shortages.add(new Shortage(sku, quantity, item.freeQuantity()));
            }
        }

        if (!shortages.isEmpty()) {
            String reason = anyUnknownSku ? "UNKNOWN_SKU" : "INSUFFICIENT_STOCK";
            return ReservationResult.failed(reason, shortages);
        }

        String reservationId = idGenerator.nextReservationId();
        Instant now = Instant.now();
        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            String sku = entry.getKey();
            int quantity = entry.getValue();
            InventoryItemEntity item = resolved.get(sku);
            item.setReservedQuantity(item.getReservedQuantity() + quantity);
            item.setUpdatedAt(now);
            reservationRepository.save(new InventoryReservationEntity(
                    reservationId + "-" + sku, orderId, sku, quantity, ReservationStatus.RESERVED, now));
        }
        return ReservationResult.reserved(reservationId);
    }

    /** @param eventKey as {@link #attemptReserve} — the PaymentRejected being applied, or null. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReleaseResult release(String orderId, ProcessedEventKey eventKey) {
        if (eventKey != null && !processedEventLedger.recordProcessed(eventKey)) {
            return ReleaseResult.DUPLICATE;
        }
        List<InventoryReservationEntity> reservations =
                reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
        if (reservations.isEmpty()) {
            return ReleaseResult.NONE;
        }
        Instant now = Instant.now();
        List<OrderLine> released = new ArrayList<>();
        String reservationGroupId = null;
        for (InventoryReservationEntity reservation : reservations) {
            InventoryItemEntity item = itemRepository.findById(reservation.getSku()).orElseThrow();
            item.setReservedQuantity(item.getReservedQuantity() - reservation.getQuantity());
            item.setUpdatedAt(now);
            reservation.setStatus(ReservationStatus.RELEASED);
            reservation.setUpdatedAt(now);
            released.add(new OrderLine(reservation.getSku(), reservation.getQuantity()));
            // per-line id is "<reservationGroupId>-<sku>" (see attemptReserve above); strip the
            // known sku suffix back off to recover the shared group id for the InventoryReleased event.
            String suffix = "-" + reservation.getSku();
            if (reservationGroupId == null && reservation.getId().endsWith(suffix)) {
                reservationGroupId = reservation.getId().substring(0, reservation.getId().length() - suffix.length());
            }
        }
        return new ReleaseResult(reservationGroupId, released);
    }
}

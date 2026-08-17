package com.orderfulfillment.monolith.inventory;

import com.orderfulfillment.monolith.common.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Split out from InventoryService so its @Transactional(REQUIRES_NEW) methods go through Spring's
 * proxy — a self-invoked call (this.attemptReserve(...)) from within InventoryService would
 * silently skip the proxy and run without a transaction/retry boundary at all.
 */
@Component
class InventoryReservationExecutor {

    private final InventoryItemRepository itemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final IdGenerator idGenerator;

    InventoryReservationExecutor(InventoryItemRepository itemRepository,
                                  InventoryReservationRepository reservationRepository,
                                  IdGenerator idGenerator) {
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.idGenerator = idGenerator;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ReservationResult attemptReserve(String orderId, List<OrderLine> lines) {
        List<Shortage> shortages = new ArrayList<>();
        boolean anyUnknownSku = false;
        List<InventoryItemEntity> resolved = new ArrayList<>();

        for (OrderLine line : lines) {
            InventoryItemEntity item = itemRepository.findById(line.sku()).orElse(null);
            if (item == null) {
                shortages.add(new Shortage(line.sku(), line.quantity(), 0));
                anyUnknownSku = true;
                continue;
            }
            resolved.add(item);
            if (item.freeQuantity() < line.quantity()) {
                shortages.add(new Shortage(line.sku(), line.quantity(), item.freeQuantity()));
            }
        }

        if (!shortages.isEmpty()) {
            String reason = anyUnknownSku ? "UNKNOWN_SKU" : "INSUFFICIENT_STOCK";
            return ReservationResult.failed(reason, shortages);
        }

        String reservationId = idGenerator.nextReservationId();
        Instant now = Instant.now();
        for (int i = 0; i < lines.size(); i++) {
            OrderLine line = lines.get(i);
            InventoryItemEntity item = resolved.get(i);
            item.setReservedQuantity(item.getReservedQuantity() + line.quantity());
            item.setUpdatedAt(now);
            reservationRepository.save(new InventoryReservationEntity(
                    reservationId + "-" + line.sku(), orderId, line.sku(), line.quantity(), ReservationStatus.RESERVED, now));
        }
        return ReservationResult.reserved(reservationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void release(String orderId) {
        List<InventoryReservationEntity> reservations =
                reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
        Instant now = Instant.now();
        for (InventoryReservationEntity reservation : reservations) {
            InventoryItemEntity item = itemRepository.findById(reservation.getSku()).orElseThrow();
            item.setReservedQuantity(item.getReservedQuantity() - reservation.getQuantity());
            item.setUpdatedAt(now);
            reservation.setStatus(ReservationStatus.RELEASED);
            reservation.setUpdatedAt(now);
        }
    }
}

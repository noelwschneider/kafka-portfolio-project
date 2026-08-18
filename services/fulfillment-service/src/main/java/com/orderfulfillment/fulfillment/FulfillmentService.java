package com.orderfulfillment.fulfillment;

import com.orderfulfillment.common.IdGenerator;
import com.orderfulfillment.common.NotFoundException;
import com.orderfulfillment.common.idempotency.ProcessedEventKey;
import com.orderfulfillment.common.idempotency.ProcessedEventLedger;
import com.orderfulfillment.fulfillment.dto.ShipmentDto;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs in-process this phase, standing in for Fulfillment Service consuming PaymentAuthorized
 * (docs/events/event-catalog.md). No carrier integration; tracking numbers are generated locally.
 */
@Service
public class FulfillmentService {

    private final ShipmentRepository repository;
    private final IdGenerator idGenerator;
    private final ProcessedEventLedger processedEventLedger;

    public FulfillmentService(ShipmentRepository repository, IdGenerator idGenerator,
                               ProcessedEventLedger processedEventLedger) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.processedEventLedger = processedEventLedger;
    }

    /**
     * Owns the business transaction ADR-005 requires: the ledger claim and the shipment insert
     * commit together, or neither does (docs/reliability-pattern.md §2). The claim is the first
     * statement, so a concurrent or replayed delivery that loses the claim never reaches
     * {@code repository.save}.
     *
     * <p>{@code shipments.order_id UNIQUE} (V1__shipments.sql) is a defense-in-depth backstop, not
     * the primary guard — the same relationship as Payment Service's
     * {@code payment_attempts.idempotency_key UNIQUE} to its own ledger check: the ledger key is the
     * Kafka {@code eventId}, so it stops a duplicate <em>delivery</em> of the same event; the unique
     * constraint is a business-level invariant ("one shipment per order") that would also catch a
     * hypothetical bug that reached this method twice for the same order under two different event
     * ids. Belt and suspenders — the ledger is expected to be the one that actually fires.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShipmentCreationResult createShipment(String orderId, ProcessedEventKey eventKey) {
        if (!processedEventLedger.recordProcessed(eventKey)) {
            return ShipmentCreationResult.DUPLICATE;
        }
        String shipmentId = idGenerator.nextShipmentId();
        String trackingNumber = "TRK-" + String.format("%09d", Math.abs(shipmentId.hashCode()) % 1_000_000_000);
        ShipmentEntity shipment = new ShipmentEntity(shipmentId, orderId, "CREATED", trackingNumber, Instant.now());
        repository.save(shipment);
        return ShipmentCreationResult.created(toDto(shipment));
    }

    @Transactional(readOnly = true)
    public ShipmentDto getByOrderId(String orderId) {
        ShipmentEntity shipment = repository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("SHIPMENT_NOT_FOUND", "No shipment for order " + orderId));
        return toDto(shipment);
    }

    private ShipmentDto toDto(ShipmentEntity shipment) {
        return new ShipmentDto(shipment.getId(), shipment.getOrderId(), shipment.getStatus(),
                shipment.getTrackingNumber(), shipment.getCreatedAt(), shipment.getUpdatedAt());
    }
}

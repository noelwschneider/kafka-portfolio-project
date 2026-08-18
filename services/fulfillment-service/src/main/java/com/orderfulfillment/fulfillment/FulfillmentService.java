package com.orderfulfillment.fulfillment;

import com.orderfulfillment.common.IdGenerator;
import com.orderfulfillment.common.NotFoundException;
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

    public FulfillmentService(ShipmentRepository repository, IdGenerator idGenerator) {
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShipmentDto createShipment(String orderId) {
        String shipmentId = idGenerator.nextShipmentId();
        String trackingNumber = "TRK-" + String.format("%09d", Math.abs(shipmentId.hashCode()) % 1_000_000_000);
        ShipmentEntity shipment = new ShipmentEntity(shipmentId, orderId, "CREATED", trackingNumber, Instant.now());
        repository.save(shipment);
        return toDto(shipment);
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

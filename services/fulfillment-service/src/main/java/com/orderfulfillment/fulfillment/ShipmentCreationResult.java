package com.orderfulfillment.fulfillment;

import com.orderfulfillment.fulfillment.dto.ShipmentDto;

/**
 * Result of {@link FulfillmentService#createShipment} — whether the consumer should publish
 * ShipmentCreated, or not (docs/events/event-catalog.md §3).
 *
 * <p>{@code duplicate} exists for the same reason Inventory Service's {@code ReservationResult} has
 * one: a duplicate delivery means some earlier delivery of the <em>same</em> event already created
 * the shipment and already published ShipmentCreated for it. Publishing again would be exactly the
 * duplicate side effect Scenario 4 exists to prove does not happen.
 */
public record ShipmentCreationResult(ShipmentDto shipment, boolean duplicate) {

    /** The event had already been processed by this consumer; nothing was created and nothing must be published. */
    public static final ShipmentCreationResult DUPLICATE = new ShipmentCreationResult(null, true);

    public static ShipmentCreationResult created(ShipmentDto shipment) {
        return new ShipmentCreationResult(shipment, false);
    }
}

package com.orderfulfillment.monolith.fulfillment;

import com.orderfulfillment.monolith.fulfillment.dto.ShipmentDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** docs/openapi/fulfillment-service.yaml's /api namespace — read-only view of shipment records. */
@RestController
@RequestMapping("/api/shipments")
public class FulfillmentController {

    private final FulfillmentService fulfillmentService;

    public FulfillmentController(FulfillmentService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping("/{orderId}")
    public ShipmentDto getShipmentByOrderId(@PathVariable String orderId) {
        return fulfillmentService.getByOrderId(orderId);
    }
}

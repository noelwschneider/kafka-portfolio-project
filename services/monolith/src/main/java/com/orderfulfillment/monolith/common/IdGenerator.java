package com.orderfulfillment.monolith.common;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Generates the short human-readable ids used as primary keys, e.g. "order-21873" (docs/db-ownership.md). */
@Component
public class IdGenerator {

    private final AtomicLong orderSeq = new AtomicLong(20000);
    private final AtomicLong reservationSeq = new AtomicLong(4000);
    private final AtomicLong paymentSeq = new AtomicLong(9000);
    private final AtomicLong shipmentSeq = new AtomicLong(1000);

    public String nextOrderId() {
        return "order-" + orderSeq.incrementAndGet();
    }

    public String nextReservationId() {
        return "resv-" + reservationSeq.incrementAndGet();
    }

    public String nextPaymentId() {
        return "pay-" + paymentSeq.incrementAndGet();
    }

    public String nextShipmentId() {
        return "shp-" + shipmentSeq.incrementAndGet();
    }
}

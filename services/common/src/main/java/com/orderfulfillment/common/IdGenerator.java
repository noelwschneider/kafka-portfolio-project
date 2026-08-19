package com.orderfulfillment.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Generates the short human-readable ids used as primary keys, e.g. "order-21873"
 * (docs/db-ownership.md), from a Postgres sequence owned by the id's own schema.
 *
 * <p>Each {@code next*Id()} method always targets the same schema regardless of which service
 * calls it (e.g. {@link #nextOrderId()} always draws from {@code order_service.order_id_seq}, even
 * when called from a test or a future cross-service tool), matching the fixed id-kind-to-schema
 * ownership in docs/db-ownership.md — so, unlike {@code ProcessedEventLedger}'s table name, this
 * needs no per-caller configuration. A DB sequence (rather than the in-memory {@code AtomicLong}
 * this replaced) survives restarts and is safe across multiple instances of the same service; see
 * docs/CHANGELOG-contracts.md for why that mattered.
 */
@Component
public class IdGenerator {

    private final JdbcClient jdbcClient;

    public IdGenerator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public String nextOrderId() {
        return "order-" + nextVal("order_service.order_id_seq");
    }

    public String nextReservationId() {
        return "resv-" + nextVal("inventory_service.reservation_id_seq");
    }

    public String nextPaymentId() {
        return "pay-" + nextVal("payment_service.payment_id_seq");
    }

    public String nextShipmentId() {
        return "shp-" + nextVal("fulfillment_service.shipment_id_seq");
    }

    private long nextVal(String sequenceName) {
        return jdbcClient.sql("SELECT nextval('" + sequenceName + "')").query(Long.class).single();
    }
}

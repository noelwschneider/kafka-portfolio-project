package com.orderfulfillment.order;

import java.util.Set;

/** docs/order-state-machine.md §1 — the frozen order lifecycle enum. Owned exclusively by Order Service. */
public enum OrderStatus {
    PENDING,
    INVENTORY_RESERVED,
    REJECTED_OUT_OF_STOCK,
    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED,
    FULFILLMENT_PENDING,
    FULFILLED,
    FAILED;

    private static final Set<OrderStatus> TERMINAL =
            Set.of(REJECTED_OUT_OF_STOCK, PAYMENT_FAILED, FULFILLED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}

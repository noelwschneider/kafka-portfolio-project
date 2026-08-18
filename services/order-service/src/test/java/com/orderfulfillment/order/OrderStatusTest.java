package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void terminalStatesMatchOrderStateMachine() {
        assertThat(OrderStatus.REJECTED_OUT_OF_STOCK.isTerminal()).isTrue();
        assertThat(OrderStatus.PAYMENT_FAILED.isTerminal()).isTrue();
        assertThat(OrderStatus.FULFILLED.isTerminal()).isTrue();
        assertThat(OrderStatus.FAILED.isTerminal()).isTrue();
    }

    @Test
    void nonTerminalStatesAreNotTerminal() {
        assertThat(OrderStatus.PENDING.isTerminal()).isFalse();
        assertThat(OrderStatus.INVENTORY_RESERVED.isTerminal()).isFalse();
        assertThat(OrderStatus.PAYMENT_PENDING.isTerminal()).isFalse();
        assertThat(OrderStatus.PAID.isTerminal()).isFalse();
        assertThat(OrderStatus.FULFILLMENT_PENDING.isTerminal()).isFalse();
    }
}

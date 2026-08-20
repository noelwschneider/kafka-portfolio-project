package com.orderfulfillment.order;

/** Lifecycle of a parked out-of-order transition — see {@code V5__deferred_transitions.sql}. */
public enum DeferredTransitionStatus {
    /** Waiting for its predecessor transition to be applied. */
    PENDING,
    /** Drained: the transition was applied and its {@code order_status_history} row written. */
    APPLIED,
    /** The order reached a terminal state this transition can never legally follow. */
    ABANDONED
}

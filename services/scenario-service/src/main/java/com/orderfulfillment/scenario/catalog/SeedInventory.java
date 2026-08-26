package com.orderfulfillment.scenario.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The seeded stock level of each demo SKU, per docs/planning/sprint-1/backend-design.md's Seed Data
 * section and docs/db-ownership.md's price table.
 *
 * <p>Two callers need this number and must not disagree about it: {@code DemoResetService}, which
 * restores every SKU on {@code POST /demo/reset}, and {@link
 * com.orderfulfillment.scenario.scenarios.HighVolumeScenario}, which restores the one SKU it bursts
 * against before each run. It previously lived only inside {@code DemoResetService}; a scenario that
 * needs to state "this is the stock level I require" cannot depend on the reset service without
 * inverting the dependency, so the constant lives here instead of being duplicated.
 */
public final class SeedInventory {

    private static final Map<String, Integer> SEED_QUANTITIES;

    static {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        quantities.put("SKU-001", 10);
        quantities.put("SKU-002", 5);
        quantities.put("SKU-003", 100);
        quantities.put("SKU-004", 2);
        SEED_QUANTITIES = Map.copyOf(quantities);
    }

    /** The SKU Scenario 8 (High-Volume Batch) bursts against — the only one seeded deep enough. */
    public static final String HIGH_VOLUME_SKU = "SKU-003";

    private SeedInventory() {
    }

    /** Every seeded SKU and its seed quantity, in a stable iteration order. */
    public static Map<String, Integer> quantities() {
        return SEED_QUANTITIES;
    }

    /** The seed quantity for {@code sku}. */
    public static int quantityFor(String sku) {
        Integer quantity = SEED_QUANTITIES.get(sku);
        if (quantity == null) {
            throw new IllegalArgumentException("No seed quantity is defined for SKU " + sku);
        }
        return quantity;
    }
}

package com.orderfulfillment.scenario.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single source of truth {@code GET /demo/scenarios} is served from, and the place
 * {@link com.orderfulfillment.scenario.scenarios.ScenarioExecutionService} looks up whether a
 * requested scenario name exists and is available before dispatching to a
 * {@link com.orderfulfillment.scenario.scenarios.ScenarioRunner}. Content mirrors docs/scenarios.md's
 * Index table and each scenario's "Demonstrates" / "Expected terminal state" sections exactly —
 * deliberately not re-derived from anywhere else, since docs/scenarios.md is frozen and this is its
 * one intended runtime mirror.
 */
@Component
public class ScenarioCatalog {

    private final Map<String, ScenarioDefinitionSpec> byName = new LinkedHashMap<>();

    public ScenarioCatalog() {
        register(new ScenarioDefinitionSpec(
                "standard-order", "Standard Fulfillment",
                "Creates an order with available inventory and successful payment.",
                List.of("REST request", "persistence", "event publication", "Kafka consumption",
                        "asynchronous workflow", "state transitions"),
                "FULFILLED", true));
        register(new ScenarioDefinitionSpec(
                "out-of-stock", "Out of Stock",
                "Creates an order requesting more inventory than exists.",
                List.of("domain validation", "inventory ownership", "rejection events",
                        "asynchronous failure propagation"),
                "REJECTED_OUT_OF_STOCK", true));
        register(new ScenarioDefinitionSpec(
                "payment-failure", "Payment Rejection",
                "Inventory reserves successfully; the payment simulator rejects authorization.",
                List.of("downstream business failure", "compensation", "inventory release",
                        "eventual state correction"),
                "PAYMENT_FAILED", true));
        register(new ScenarioDefinitionSpec(
                "duplicate-event", "Duplicate Event Delivery",
                "Delivers the same logical event twice and shows no duplicate side effect.",
                List.of("at-least-once processing assumptions", "event IDs", "idempotent consumers",
                        "duplicate detection"),
                null, true));
        register(new ScenarioDefinitionSpec(
                "consumer-outage", "Consumer Outage and Recovery",
                "Temporarily stops a consumer, publishes work, then restores processing.",
                List.of("Kafka durability", "offsets", "asynchronous decoupling", "consumer recovery"),
                null, true));
        register(new ScenarioDefinitionSpec(
                "poison-message", "Poison Message / DLQ",
                "Publishes an event that repeatedly fails processing and lands in the dead-letter topic.",
                List.of("retry policy", "backoff", "bounded failure", "dead-letter routing",
                        "operational troubleshooting"),
                null, true));
        register(new ScenarioDefinitionSpec(
                "inventory-contention", "Inventory Contention",
                "Two concurrent orders compete for the last units of SKU-004.",
                List.of("concurrent access", "transaction isolation", "locking/versioning",
                        "consistency under contention"),
                null, true));
        register(new ScenarioDefinitionSpec(
                "high-volume", "High-Volume Batch",
                "Generates many orders quickly and observes throughput/lag. Lands in Phase 10.",
                List.of("event throughput", "consumer groups", "horizontal scaling",
                        "lag/processing behavior"),
                null, false));
    }

    private void register(ScenarioDefinitionSpec spec) {
        byName.put(spec.name(), spec);
    }

    public List<ScenarioDefinitionSpec> all() {
        return List.copyOf(byName.values());
    }

    public Optional<ScenarioDefinitionSpec> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}

package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.scenario.catalog.SeedInventory;
import com.orderfulfillment.scenario.clients.InventoryServiceClient;
import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.domain.TimelineKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * docs/scenarios.md Scenario 7 — Inventory Contention. Two genuinely concurrent
 * {@code POST /api/orders} calls for SKU-004 (seeded stock: 2), each requesting 2 units — Inventory
 * Service's optimistic-locking reservation logic (docs/db-ownership.md's {@code version} column) is
 * what decides the winner and loser; this scenario only has to make the two requests actually overlap.
 *
 * <p>Restores SKU-004 to its seeded 2 units before each run (see {@link #restoreContentionSkuToSeed}),
 * the same precondition-establishing fix {@link HighVolumeScenario#restoreBurstSkuToSeed} applies for
 * SKU-003: a fulfilled order's reservation is never released, so without the restore the first run
 * after any reset consumes SKU-004 entirely and every run after that fails out-of-stock instead of
 * exercising the intended contention.
 */
@Component
public class InventoryContentionScenario extends AbstractScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(InventoryContentionScenario.class);

    private static final String CONTENTION_SKU = "SKU-004";

    private final InventoryServiceClient inventoryServiceClient;

    public InventoryContentionScenario(ScenarioToolkit toolkit, InventoryServiceClient inventoryServiceClient) {
        super(toolkit);
        this.inventoryServiceClient = inventoryServiceClient;
    }

    @Override
    public String scenarioName() {
        return "inventory-contention";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("DEFAULT_SUCCESS"));
        restoreContentionSkuToSeed(ctx);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<OrderServiceClient.OrderCreationResult> first =
                    CompletableFuture.supplyAsync(() -> createOnThisCorrelation(ctx), pool);
            CompletableFuture<OrderServiceClient.OrderCreationResult> second =
                    CompletableFuture.supplyAsync(() -> createOnThisCorrelation(ctx), pool);
            CompletableFuture.allOf(first, second).join();

            OrderServiceClient.OrderCreationResult orderA = first.join();
            OrderServiceClient.OrderCreationResult orderB = second.join();

            CompletableFuture<Void> watchA = CompletableFuture.runAsync(
                    () -> watchOnThisCorrelation(ctx, orderA.orderId()), pool);
            CompletableFuture<Void> watchB = CompletableFuture.runAsync(
                    () -> watchOnThisCorrelation(ctx, orderB.orderId()), pool);
            CompletableFuture.allOf(watchA, watchB).join();
        } finally {
            pool.shutdown();
        }
        // orderId is deliberately left null on the run: this scenario creates two orders, and each is
        // named individually in its own timeline entries (ScenarioRun.orderId's documented meaning).
    }

    /**
     * Puts {@code SKU-004} back to its seed level of 2 before the two contending orders are created,
     * the same way {@link HighVolumeScenario#restoreBurstSkuToSeed} does for SKU-003. Without this, a
     * fulfilled order's reservation is never released, so a second run in a row (without an
     * intervening {@code POST /demo/reset}) starts from 0 available units instead of 2 and fails
     * out-of-stock rather than exercising contention.
     *
     * <p>Recorded on the timeline with its real status code and deliberately not fatal on failure: if
     * the restore does not succeed the run should report the honest downstream out-of-stock outcome
     * plus the failed restore, rather than masking it behind a different exception.
     */
    private void restoreContentionSkuToSeed(ScenarioRunContext ctx) {
        String label = "POST /demo/inventory/" + CONTENTION_SKU + "/restore";
        try {
            int statusCode = inventoryServiceClient.restoreInventory(
                    CONTENTION_SKU, SeedInventory.quantityFor(CONTENTION_SKU));
            recordHttp(ctx.runId(), label, statusCode);
        } catch (Exception e) {
            log.warn("Could not restore seed stock for {} before the inventory-contention run", CONTENTION_SKU, e);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sku", CONTENTION_SKU);
            detail.put("error", e.getMessage());
            timelineRecorder.append(ctx.runId(), TimelineKind.HTTP, label, detail);
        }
    }

    private OrderServiceClient.OrderCreationResult createOnThisCorrelation(ScenarioRunContext ctx) {
        return runOnThisCorrelation(ctx, () -> createOrder(ctx.runId(), "SKU-004", 2, "Cara Contention"));
    }

    private void watchOnThisCorrelation(ScenarioRunContext ctx, String orderId) {
        runOnThisCorrelation(ctx, () -> {
            orderStatusWatcher.awaitTerminal(ctx.runId(), orderId);
            return null;
        });
    }

    /** Each pool thread starts with no correlationId of its own; propagate the run's explicitly, the
     * same way CorrelationIdFilter does per HTTP request (see CorrelationIdHolder's Javadoc). */
    private <T> T runOnThisCorrelation(ScenarioRunContext ctx, java.util.function.Supplier<T> action) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        CorrelationIdHolder.runInScope(ctx.correlationId(), () -> result.set(action.get()));
        return result.get();
    }
}

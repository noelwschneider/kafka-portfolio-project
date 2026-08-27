package com.orderfulfillment.scenario.scenarios;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.kafka.KafkaTopics;
import com.orderfulfillment.scenario.catalog.SeedInventory;
import com.orderfulfillment.scenario.clients.InventoryServiceClient;
import com.orderfulfillment.scenario.clients.OrderServiceClient;
import com.orderfulfillment.scenario.config.ScenarioProperties;
import com.orderfulfillment.scenario.domain.TimelineKind;
import com.orderfulfillment.scenario.runtime.ConsumerLagService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * docs/scenarios.md Scenario 8 — High-Volume Batch (Phase 10 — Scaling Demo). Restores SKU-003 to its
 * seeded 100 units (see {@link #restoreBurstSkuToSeed} — each run consumes 60 of them permanently, so
 * this is a precondition the scenario must establish rather than assume), bursts
 * {@code POST /api/orders} for 1 x SKU-003 across a small client-side thread pool, then samples
 * Inventory Service's real consumer-group lag
 * ({@link ConsumerLagService}, group {@code inventory-service} on {@link KafkaTopics#ORDERS_EVENTS})
 * while the backlog drains, and finally confirms every order it created actually reached
 * {@code FULFILLED} — the scenario's documented success condition ("Throughput and lag are
 * observable, and orders reach FULFILLED without loss").
 *
 * <p>Burst size and submission concurrency are configurable
 * ({@code orderfulfillment.scenario.high-volume-*}, see application.yml) precisely so this phase's
 * real 1/2/3-replica measurements (docs/agent-reports/phase-10-scaling-demo.md) could tune them
 * without a code change. Like {@link InventoryContentionScenario}, this scenario creates many
 * orders rather than one, so the run's own {@code orderId} is left null.
 */
@Component
public class HighVolumeScenario extends AbstractScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(HighVolumeScenario.class);

    private static final String INVENTORY_CONSUMER_GROUP = "inventory-service";

    /** Names every order this scenario's burst creates so they read unmistakably on the Orders page. */
    private static final String CUSTOMER_NAME = "Hank Highvolume";

    private final ScenarioProperties properties;
    private final ConsumerLagService consumerLagService;
    private final InventoryServiceClient inventoryServiceClient;

    public HighVolumeScenario(ScenarioToolkit toolkit, ScenarioProperties properties,
                               ConsumerLagService consumerLagService,
                               InventoryServiceClient inventoryServiceClient) {
        super(toolkit);
        this.properties = properties;
        this.consumerLagService = consumerLagService;
        this.inventoryServiceClient = inventoryServiceClient;
    }

    @Override
    public String scenarioName() {
        return "high-volume";
    }

    @Override
    public void run(ScenarioRunContext ctx) {
        recordHttp(ctx.runId(), "PUT /demo/payment-behavior", paymentServiceClient.setBehavior("DEFAULT_SUCCESS"));
        restoreBurstSkuToSeed(ctx);

        int burstSize = properties.highVolumeBurstSize();
        int concurrency = Math.max(1, properties.highVolumeSubmissionConcurrency());

        // Lag is sampled on its own thread starting now and running through submission AND drain,
        // not sequentially after submission finishes — Inventory Service's consumer group starts
        // draining the backlog the moment the first burst event lands, concurrently with the rest
        // of the burst still being submitted, so a "submit, then sample" ordering would frequently
        // observe an already-drained (or partially drained) backlog instead of the real peak.
        List<Map<String, Object>> lagSamples = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stopSampling = new AtomicBoolean(false);
        Thread samplerThread = new Thread(() -> sampleConsumerLagUntilDrainedOrStopped(lagSamples, stopSampling),
                "high-volume-lag-sampler");
        samplerThread.start();

        List<String> orderIds = Collections.synchronizedList(new ArrayList<>());
        long submissionDurationMs = submitBurst(ctx, burstSize, concurrency, orderIds);
        double throughput = submissionDurationMs == 0
                ? orderIds.size()
                : orderIds.size() * 1000.0 / submissionDurationMs;

        Map<String, Object> submissionDetail = new LinkedHashMap<>();
        submissionDetail.put("burstSizeRequested", burstSize);
        submissionDetail.put("ordersSubmitted", orderIds.size());
        submissionDetail.put("submissionDurationMs", submissionDurationMs);
        submissionDetail.put("submissionThroughputOrdersPerSec", Math.round(throughput * 100.0) / 100.0);
        timelineRecorder.append(ctx.runId(), TimelineKind.HTTP, "Burst order submission complete", submissionDetail);

        BatchOutcome outcome = watchAllToTerminal(ctx, orderIds, concurrency);

        stopSampling.set(true);
        joinQuietly(samplerThread);
        long peakLag = lagSamples.stream().mapToLong(s -> ((Number) s.get("lag")).longValue()).max().orElse(0);
        Map<String, Object> lagDetail = new LinkedHashMap<>();
        lagDetail.put("consumerGroup", INVENTORY_CONSUMER_GROUP);
        lagDetail.put("topic", KafkaTopics.ORDERS_EVENTS);
        lagDetail.put("samples", new ArrayList<>(lagSamples));
        lagDetail.put("consumerLagAtPeakBacklog", peakLag);
        timelineRecorder.append(ctx.runId(), TimelineKind.EVENT,
                "Consumer lag observed while backlog drains", lagDetail);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ordersSubmitted", orderIds.size());
        summary.put("ordersFulfilled", outcome.fulfilled());
        summary.put("ordersNotFulfilled", outcome.notFulfilled());
        summary.put("drainDurationMs", outcome.drainDurationMs());
        summary.put("endToEndDurationMs", submissionDurationMs + outcome.drainDurationMs());
        timelineRecorder.append(ctx.runId(), TimelineKind.STATE_CHANGE, "High-volume batch summary", summary);

        if (outcome.notFulfilled() > 0) {
            throw new IllegalStateException(outcome.notFulfilled() + " of " + orderIds.size()
                    + " orders in the high-volume batch did not reach FULFILLED");
        }
        // orderId is deliberately left null: this scenario creates many orders, not one (same
        // reasoning as InventoryContentionScenario).
    }

    /**
     * Puts {@link SeedInventory#HIGH_VOLUME_SKU} back to its seed level before the burst, the same way
     * the {@code PUT /demo/payment-behavior} call above puts Payment Service back to a known mode.
     *
     * <p>Without this, the scenario silently depends on being run exactly once per {@code POST
     * /demo/reset}. Each run permanently consumes {@code high-volume-burst-size} (60) of SKU-003's 100
     * seeded units — a fulfilled order's reservation is never released — so a second run in a row has
     * only 40 units left and fails 20 of 60 on {@code InventoryReservationFailed}, and a third fails
     * all 60. That is exactly the escalating 0 -> 20/60 -> 60/60 failure observed live on the demo box
     * (see docs/agent-reports/sprint-8/high-volume-scenario-stock-depletion-and-sse-refetch-storm.md).
     * The demo is a shared public sandbox (ADR-010) where nobody is obliged to press reset between
     * runs, so the scenario has to establish its own precondition rather than assume one.
     *
     * <p>Recorded on the timeline with its real status code and deliberately not fatal on failure: if
     * the restore does not succeed the run should report the honest downstream out-of-stock outcome
     * plus the failed restore, rather than masking it behind a different exception.
     */
    private void restoreBurstSkuToSeed(ScenarioRunContext ctx) {
        String sku = SeedInventory.HIGH_VOLUME_SKU;
        String label = "POST /demo/inventory/" + sku + "/restore";
        try {
            int statusCode = inventoryServiceClient.restoreInventory(sku, SeedInventory.quantityFor(sku));
            recordHttp(ctx.runId(), label, statusCode);
        } catch (Exception e) {
            log.warn("Could not restore seed stock for {} before the high-volume burst", sku, e);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sku", sku);
            detail.put("error", e.getMessage());
            timelineRecorder.append(ctx.runId(), TimelineKind.HTTP, label, detail);
        }
    }

    private long submitBurst(ScenarioRunContext ctx, int burstSize, int concurrency, List<String> orderIds) {
        ExecutorService submitPool = Executors.newFixedThreadPool(concurrency);
        long start = System.currentTimeMillis();
        try {
            List<CompletableFuture<Void>> submissions = new ArrayList<>();
            for (int i = 0; i < burstSize; i++) {
                submissions.add(CompletableFuture.runAsync(() -> runOnThisCorrelation(ctx, () -> {
                    OrderServiceClient.OrderCreationResult result =
                            createOrder(ctx.runId(), "SKU-003", 1, CUSTOMER_NAME);
                    if (result.orderId() != null) {
                        orderIds.add(result.orderId());
                    }
                    return null;
                }), submitPool));
            }
            CompletableFuture.allOf(submissions.toArray(CompletableFuture[]::new)).join();
        } finally {
            submitPool.shutdown();
        }
        return System.currentTimeMillis() - start;
    }

    /** Runs on its own thread (see {@link #run}), sampling until the backlog is observed drained
     * (lag back to 0 after having been above 0 at least once), the caller signals {@code stop}, or
     * the configured timeout elapses — whichever comes first. */
    private void sampleConsumerLagUntilDrainedOrStopped(List<Map<String, Object>> samples, AtomicBoolean stop) {
        long deadline = System.currentTimeMillis() + properties.highVolumeLagPollTimeoutMs();
        boolean everBacklogged = false;
        while (System.currentTimeMillis() < deadline) {
            long lag = consumerLagService.totalLag(INVENTORY_CONSUMER_GROUP, KafkaTopics.ORDERS_EVENTS);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("atMs", System.currentTimeMillis());
            sample.put("lag", lag);
            samples.add(sample);
            everBacklogged = everBacklogged || lag > 0;
            if (stop.get() || (everBacklogged && lag == 0)) {
                return;
            }
            sleep(properties.highVolumeLagPollIntervalMs());
        }
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record BatchOutcome(long fulfilled, long notFulfilled, long drainDurationMs) {
    }

    private BatchOutcome watchAllToTerminal(ScenarioRunContext ctx, List<String> orderIds, int concurrency) {
        long start = System.currentTimeMillis();
        List<AtomicReference<String>> outcomes = new ArrayList<>();
        ExecutorService watchPool = Executors.newFixedThreadPool(concurrency);
        try {
            List<CompletableFuture<Void>> watches = new ArrayList<>();
            for (String orderId : orderIds) {
                AtomicReference<String> outcome = new AtomicReference<>();
                outcomes.add(outcome);
                watches.add(CompletableFuture.runAsync(() -> runOnThisCorrelation(ctx, () -> {
                    // Poll-only, deliberately not the SSE-first awaitTerminal every other scenario
                    // uses — see OrderStatusWatcher.awaitTerminalPollOnly's Javadoc for the real,
                    // live-found reason this scenario avoids dozens of concurrent SSE connections.
                    outcome.set(orderStatusWatcher.awaitTerminalPollOnly(
                            ctx.runId(), orderId, properties.highVolumeOrderWatchTimeoutMs()));
                    return null;
                }), watchPool));
            }
            CompletableFuture.allOf(watches.toArray(CompletableFuture[]::new)).join();
        } finally {
            watchPool.shutdown();
        }
        long drainDurationMs = System.currentTimeMillis() - start;
        long fulfilled = outcomes.stream().filter(o -> "FULFILLED".equals(o.get())).count();
        return new BatchOutcome(fulfilled, outcomes.size() - fulfilled, drainDurationMs);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Each pool thread starts with no correlationId of its own; propagate the run's explicitly, same
     * as InventoryContentionScenario does for its own two-thread fan-out. */
    private <T> T runOnThisCorrelation(ScenarioRunContext ctx, Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        CorrelationIdHolder.runInScope(ctx.correlationId(), () -> result.set(action.get()));
        return result.get();
    }
}

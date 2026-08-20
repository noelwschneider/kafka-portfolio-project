package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Scenario 7 (Inventory Contention), proven against a real Postgres with real concurrent
 * transactions — the gap docs/agent-reports/phase-3-boundary.md §11 left open.
 *
 * <p>{@link InventoryServiceOptimisticLockTest} proves the retry <em>wrapper</em> reacts correctly
 * to an {@code ObjectOptimisticLockingFailureException} that a Mockito stub was told to throw. It
 * cannot prove that Postgres + Hibernate's {@code @Version} column actually produces that exception
 * when two real transactions race for the same row. This test does: N threads released
 * simultaneously by a {@link CyclicBarrier}, each running the real
 * {@code InventoryService.reserve} path (real {@code REQUIRES_NEW} transaction, real connection
 * from the shared pool, real Testcontainers Postgres), repeated over many rounds so a pass by
 * timing luck is implausible.
 *
 * <p>The end-to-end Kafka-consumer variant of the same invariant lives in
 * {@link InventoryKafkaConcurrencyIntegrationTest}.
 */
class InventoryConcurrencyIntegrationTest extends AbstractIntegrationTest {

    /** docs/planning/sprint-1/frontend-design.md Scenario 7 / docs/scenarios.md: SKU-004 is seeded at 2. */
    private static final String SCARCE_SKU = "SKU-004";
    private static final int SCARCE_STOCK = 2;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * Scenario 7 literally: stock 2, several orders each asking for the whole 2. At most one can
     * win; every loser must fail cleanly with INSUFFICIENT_STOCK and leave no reservation row.
     * More than two contenders per round (and many rounds) so the conflict path is hit repeatedly
     * rather than occasionally.
     */
    @Test
    void concurrentOrdersForTheWholeScarceStockNeverOversell() {
        int contenders = 8;
        int rounds = 15;
        long conflictsBefore = inventoryService.optimisticLockConflictCount();

        for (int r = 0; r < rounds; r++) {
            final int round = r;
            resetSku(SCARCE_SKU, SCARCE_STOCK);
            String roundTag = "r" + round + "-" + UUID.randomUUID();

            List<Outcome> outcomes = raceReservations(contenders, roundTag, SCARCE_SKU, SCARCE_STOCK);

            assertThat(outcomes).allSatisfy(o -> assertThat(o.error)
                    .as("no exception may escape reserve() in round " + round)
                    .isNull());

            List<Outcome> won = outcomes.stream().filter(o -> o.result.success()).toList();
            List<Outcome> lost = outcomes.stream().filter(o -> !o.result.success()).toList();

            assertThat(won).as("exactly one order may win 2-of-2 stock in round " + round).hasSize(1);
            assertThat(lost).hasSize(contenders - 1);
            assertThat(lost).allSatisfy(o -> {
                assertThat(o.result.failureReason()).isEqualTo("INSUFFICIENT_STOCK");
                assertThat(o.result.shortages()).singleElement()
                        .satisfies(s -> assertThat(s.sku()).isEqualTo(SCARCE_SKU));
            });

            // The persisted truth, not the return values: reserved must land exactly on the
            // available stock, never above it.
            assertThat(reservedQuantity(SCARCE_SKU)).as("reserved_quantity after round " + round)
                    .isEqualTo(SCARCE_STOCK);
            assertThat(freeQuantity(SCARCE_SKU)).isEqualTo(0);
            assertThat(reservationRowCount(roundTag)).as("only the winner may leave a reservation row")
                    .isEqualTo(1);
            assertThat(reservedRowsFor(roundTag)).isEqualTo(won.get(0).orderId);
        }

        // Proves the race was genuinely raced: if no version conflict ever occurred across 15
        // rounds of 8 simultaneous writers, the threads were being serialized somewhere and the
        // assertions above would have been vacuous.
        assertThat(inventoryService.optimisticLockConflictCount() - conflictsBefore)
                .as("real @Version conflicts observed against Postgres")
                .isGreaterThan(0);
    }

    /**
     * Higher-contention variant: more contenders than stock, each asking for one unit of a SKU
     * with room for many winners, so many conflicting writes to the same row pile up in one round.
     * This is the case that actually stresses the retry budget — a losing thread here must still
     * end up with a clean INSUFFICIENT_STOCK, never a propagated
     * {@code ObjectOptimisticLockingFailureException} (which would escape the @KafkaListener and
     * leave the order with no InventoryReservationFailed at all).
     */
    @Test
    void highContentionSingleUnitOrdersReserveExactlyTheAvailableStock() {
        String sku = "SKU-001";
        int stock = 10;
        int contenders = 24;
        int rounds = 10;

        for (int r = 0; r < rounds; r++) {
            final int round = r;
            resetSku(sku, stock);
            String roundTag = "hc" + round + "-" + UUID.randomUUID();

            List<Outcome> outcomes = raceReservations(contenders, roundTag, sku, 1);

            assertThat(outcomes).allSatisfy(o -> assertThat(o.error)
                    .as("no exception may escape reserve() in round " + round)
                    .isNull());
            assertThat(outcomes.stream().filter(o -> o.result.success()).count())
                    .as("winners in round " + round).isEqualTo(stock);
            assertThat(reservedQuantity(sku)).as("reserved_quantity after round " + round).isEqualTo(stock);
            assertThat(freeQuantity(sku)).isEqualTo(0);
            assertThat(reservationRowCount(roundTag)).isEqualTo(stock);
        }
    }

    /**
     * Regression test for a single-threaded oversell of the same invariant, found while proving the
     * concurrent one. An order carrying the same SKU on two lines was checked line-by-line against
     * the unmutated free quantity, so 2 + 2 against a stock of 2 passed both checks and then applied
     * both increments — reserved_quantity 4 against available_quantity 2, with only one reservation
     * row of quantity 2 to release later. No lock is involved; the mocked unit tests never modelled
     * a repeated SKU. See InventoryReservationExecutor.attemptReserve.
     */
    @Test
    void oneOrderRepeatingTheSameSkuCannotOversellIt() {
        resetSku(SCARCE_SKU, SCARCE_STOCK);
        String orderId = "order-dup-" + UUID.randomUUID();

        ReservationResult result = inventoryService.reserve(orderId,
                List.of(new OrderLine(SCARCE_SKU, SCARCE_STOCK), new OrderLine(SCARCE_SKU, SCARCE_STOCK)));

        assertThat(result.success()).as("4 units of a 2-unit SKU must not reserve").isFalse();
        assertThat(result.failureReason()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(result.shortages()).singleElement().satisfies(s -> {
            assertThat(s.sku()).isEqualTo(SCARCE_SKU);
            assertThat(s.requested()).as("both lines must be summed, not reported separately")
                    .isEqualTo(SCARCE_STOCK * 2);
            assertThat(s.available()).isEqualTo(SCARCE_STOCK);
        });
        assertThat(reservedQuantity(SCARCE_SKU)).isEqualTo(0);
    }

    /** The same order shape that fits: two lines summing to exactly the free stock reserve once. */
    @Test
    void oneOrderRepeatingTheSameSkuWithinStockReservesTheSummedQuantityOnce() {
        resetSku(SCARCE_SKU, SCARCE_STOCK);
        String orderId = "order-dup-ok-" + UUID.randomUUID();

        ReservationResult result = inventoryService.reserve(orderId,
                List.of(new OrderLine(SCARCE_SKU, 1), new OrderLine(SCARCE_SKU, 1)));

        assertThat(result.success()).isTrue();
        assertThat(reservedQuantity(SCARCE_SKU)).isEqualTo(SCARCE_STOCK);
        assertThat(freeQuantity(SCARCE_SKU)).isEqualTo(0);
        // One row per (order, SKU) — what the frozen UNIQUE (order_id, sku) constraint assumes —
        // carrying the summed quantity, so releasing it hands back everything that was taken.
        assertThat(jdbcTemplate.queryForList(
                "SELECT quantity FROM inventory_service.inventory_reservations WHERE order_id = ?",
                Integer.class, orderId)).containsExactly(SCARCE_STOCK);
    }

    /**
     * These tests deliberately drive SKUs to exhaustion, so they must hand the shared Testcontainers
     * database back in its seeded state (V2__seed_data.sql) — the other tests in this module read
     * live stock levels and would otherwise fail depending on execution order.
     */
    @AfterEach
    void restoreSeedState() {
        restoreSku(SCARCE_SKU, SCARCE_STOCK);
        restoreSku("SKU-001", 10);
    }

    private void restoreSku(String sku, int available) {
        jdbcTemplate.update("DELETE FROM inventory_service.inventory_reservations WHERE sku = ?", sku);
        jdbcTemplate.update("UPDATE inventory_service.inventory_items "
                + "SET available_quantity = ?, reserved_quantity = 0, updated_at = now() WHERE sku = ?",
                available, sku);
    }

    /** Fires {@code contenders} reservations that all start at the same instant. */
    private List<Outcome> raceReservations(int contenders, String roundTag, String sku, int quantity) {
        CyclicBarrier startLine = new CyclicBarrier(contenders);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Callable<Outcome>> tasks = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                String orderId = "order-" + roundTag + "-" + i;
                tasks.add(() -> {
                    startLine.await();
                    try {
                        return new Outcome(orderId,
                                inventoryService.reserve(orderId, List.of(new OrderLine(sku, quantity))), null);
                    } catch (RuntimeException e) {
                        return new Outcome(orderId, null, e);
                    }
                });
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : pool.invokeAll(tasks)) {
                outcomes.add(future.get());
            }
            return outcomes;
        } catch (Exception e) {
            throw new IllegalStateException("race harness failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private record Outcome(String orderId, ReservationResult result, RuntimeException error) {
    }

    private void resetSku(String sku, int available) {
        jdbcTemplate.update("UPDATE inventory_service.inventory_items "
                + "SET available_quantity = ?, reserved_quantity = 0, updated_at = now() WHERE sku = ?",
                available, sku);
        jdbcTemplate.update("DELETE FROM inventory_service.inventory_reservations WHERE sku = ?", sku);
    }

    private int reservedQuantity(String sku) {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM inventory_service.inventory_items WHERE sku = ?", Integer.class, sku);
    }

    private int freeQuantity(String sku) {
        return jdbcTemplate.queryForObject(
                "SELECT available_quantity - reserved_quantity FROM inventory_service.inventory_items WHERE sku = ?",
                Integer.class, sku);
    }

    private int reservationRowCount(String roundTag) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_service.inventory_reservations "
                        + "WHERE order_id LIKE ? AND status = 'RESERVED'",
                Integer.class, "order-" + roundTag + "-%");
    }

    private String reservedRowsFor(String roundTag) {
        return jdbcTemplate.queryForObject(
                "SELECT order_id FROM inventory_service.inventory_reservations "
                        + "WHERE order_id LIKE ? AND status = 'RESERVED'",
                String.class, "order-" + roundTag + "-%");
    }
}

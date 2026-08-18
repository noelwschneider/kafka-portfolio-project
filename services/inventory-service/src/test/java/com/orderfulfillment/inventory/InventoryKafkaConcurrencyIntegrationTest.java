package com.orderfulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderfulfillment.common.CorrelationIdHolder;
import com.orderfulfillment.common.events.EventItem;
import com.orderfulfillment.common.events.OrderCreatedPayload;
import com.orderfulfillment.common.kafka.EventTypes;
import com.orderfulfillment.common.kafka.KafkaTopics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Scenario 7 driven through the real path end to end: real {@code OrderCreated} records on
 * {@code orders.events}, consumed by the real {@link InventoryOrderEventsConsumer}
 * {@code @KafkaListener}, resolved against a real Postgres, with the real
 * {@code InventoryReserved}/{@code InventoryReservationFailed} records read back off
 * {@code inventory.events}.
 *
 * <p>Two things make the race genuine rather than hopeful:
 * <ol>
 *   <li>{@code spring.kafka.listener.concurrency: 3} (see application.yml) gives this one instance
 *       one consumer thread per partition. At the framework default of 1 the listener would process
 *       every partition on a single thread and no two OrderCreated records could ever contend
 *       inside this JVM — the assertions below would pass without ever exercising the lock.</li>
 *   <li>The listener containers are stopped, all records are published to <em>distinct</em>
 *       partitions (chosen by computing Kafka's own default partitioner over candidate order ids),
 *       and only then are the containers started — so the consumer threads pick their backlog up
 *       simultaneously instead of trickling in one record at a time.</li>
 * </ol>
 *
 * <p>{@link InventoryConcurrencyIntegrationTest} is the finer-grained sibling: it drives the same
 * reservation path from a {@link java.util.concurrent.CyclicBarrier} so simultaneity is exact and
 * can be repeated many times cheaply. This test is the proof that the same guarantee survives the
 * real consumer wiring.
 */
class InventoryKafkaConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String SCARCE_SKU = "SKU-004";
    private static final int SCARCE_STOCK = 2;
    private static final int PARTITIONS = 3;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void simultaneousOrderCreatedEventsForTheScarceSkuReserveItExactlyOnce() {
        resetSku();
        String runTag = UUID.randomUUID().toString().substring(0, 8);
        // One order per partition, each asking for the entire stock of 2 — Scenario 7's example,
        // widened from 2 orders to 3 so every partition (and therefore every consumer thread)
        // carries a contender.
        List<String> orderIds = orderIdPerPartition(runTag);

        Consumer<String, String> inventoryEvents = rawConsumer(KafkaTopics.INVENTORY_EVENTS);
        try {
            // Drain anything already on the topic so the assertions only see this run's records.
            KafkaTestUtils.getRecords(inventoryEvents, Duration.ofSeconds(2));

            stopListeners();
            for (String orderId : orderIds) {
                CorrelationIdHolder.runInScope(UUID.randomUUID(), () ->
                        eventPublisher.publish(KafkaTopics.ORDERS_EVENTS, EventTypes.ORDER_CREATED, orderId,
                                new OrderCreatedPayload(orderId, "demo-customer",
                                        List.of(new EventItem(SCARCE_SKU, SCARCE_STOCK)))));
            }
            startListeners();

            List<ConsumerRecord<String, String>> observed = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                ConsumerRecords<String, String> batch =
                        KafkaTestUtils.getRecords(inventoryEvents, Duration.ofSeconds(2));
                batch.forEach(r -> {
                    if (orderIds.contains(r.key())) {
                        observed.add(r);
                    }
                });
                assertThat(observed).hasSize(orderIds.size());
            });

            List<String> reserved = observed.stream()
                    .filter(r -> r.value().contains("\"eventType\":\"" + EventTypes.INVENTORY_RESERVED + "\""))
                    .map(ConsumerRecord::key).toList();
            List<String> failed = observed.stream()
                    .filter(r -> r.value().contains("\"eventType\":\"" + EventTypes.INVENTORY_RESERVATION_FAILED + "\""))
                    .map(ConsumerRecord::key).toList();

            assertThat(reserved).as("exactly one order may win the whole scarce stock").hasSize(1);
            assertThat(failed).as("every other contender must be told cleanly that it lost")
                    .hasSize(orderIds.size() - 1);
            assertThat(observed.stream().filter(r ->
                    r.value().contains("\"reason\":\"INSUFFICIENT_STOCK\"")).count()).isEqualTo(failed.size());

            // The invariant, read from the database rather than inferred from the events.
            assertThat(reservedQuantity()).isEqualTo(SCARCE_STOCK);
            assertThat(freeQuantity()).isEqualTo(0);
            assertThat(reservationRowCount(runTag)).isEqualTo(1);
            assertThat(reservedOrderId(runTag)).isEqualTo(reserved.get(0));
        } finally {
            inventoryEvents.close();
        }
    }

    /**
     * Picks one order id per partition using Kafka's own default partitioning of the record key
     * (murmur2 of the key bytes, sign-masked, modulo partition count — {@code EventPublisher} keys
     * every record by aggregateId = orderId). Without this, three random order ids can easily hash
     * onto one partition, where Kafka's per-partition ordering would serialize them and there would
     * be no race left to observe.
     */
    private List<String> orderIdPerPartition(String runTag) {
        List<String> chosen = new ArrayList<>();
        Set<Integer> covered = new HashSet<>();
        for (int i = 0; covered.size() < PARTITIONS && i < 10_000; i++) {
            String candidate = "order-conc-" + runTag + "-" + i;
            int partition = Utils.toPositive(Utils.murmur2(candidate.getBytes())) % PARTITIONS;
            if (covered.add(partition)) {
                chosen.add(candidate);
            }
        }
        assertThat(chosen).as("one contender per partition").hasSize(PARTITIONS);
        return chosen;
    }

    private void stopListeners() {
        listenerRegistry.getListenerContainers().forEach(MessageListenerContainer::stop);
        await().atMost(Duration.ofSeconds(30)).until(() ->
                listenerRegistry.getListenerContainers().stream().noneMatch(MessageListenerContainer::isRunning));
    }

    private void startListeners() {
        listenerRegistry.getListenerContainers().forEach(MessageListenerContainer::start);
    }

    /**
     * This test drives SKU-004 to exhaustion, so it must hand the shared Testcontainers database
     * back in its seeded state (V2__seed_data.sql) — sibling tests read live stock levels and would
     * otherwise fail depending on execution order. Listener containers are restarted too, in case
     * the test failed between stop and start.
     */
    @AfterEach
    void restoreSeedState() {
        startListeners();
        resetSku();
    }

    private void resetSku() {
        jdbcTemplate.update("UPDATE inventory_service.inventory_items "
                + "SET available_quantity = ?, reserved_quantity = 0, updated_at = now() WHERE sku = ?",
                SCARCE_STOCK, SCARCE_SKU);
        jdbcTemplate.update("DELETE FROM inventory_service.inventory_reservations WHERE sku = ?", SCARCE_SKU);
    }

    private int reservedQuantity() {
        return jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM inventory_service.inventory_items WHERE sku = ?",
                Integer.class, SCARCE_SKU);
    }

    private int freeQuantity() {
        return jdbcTemplate.queryForObject(
                "SELECT available_quantity - reserved_quantity FROM inventory_service.inventory_items WHERE sku = ?",
                Integer.class, SCARCE_SKU);
    }

    private int reservationRowCount(String runTag) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_service.inventory_reservations "
                        + "WHERE order_id LIKE ? AND status = 'RESERVED'",
                Integer.class, "order-conc-" + runTag + "-%");
    }

    private String reservedOrderId(String runTag) {
        return jdbcTemplate.queryForObject(
                "SELECT order_id FROM inventory_service.inventory_reservations "
                        + "WHERE order_id LIKE ? AND status = 'RESERVED'",
                String.class, "order-conc-" + runTag + "-%");
    }
}

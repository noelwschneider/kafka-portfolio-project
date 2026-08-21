package com.orderfulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderfulfillment.common.idempotency.ProcessedEventRetentionScheduler;
import com.orderfulfillment.order.dto.CreateOrderItem;
import com.orderfulfillment.order.dto.CreateOrderRequest;
import com.orderfulfillment.order.dto.OrderAccepted;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sprint 2 goal 2, item 4 — retention for {@code processed_events} (ADR-005) and
 * {@code deferred_transitions} (ADR-009), both flagged in their respective ADRs as growing without
 * bound with no stated cleanup. Order Service is the natural place to prove both: it is the only
 * service with a {@code deferred_transitions} table, and it already has {@code processed_events}
 * wired up ({@link ProcessedEventRetentionScheduler} is a shared {@code common} bean, active here
 * the same way it is in Inventory/Payment/Fulfillment Service).
 *
 * <p>Real rows, inserted with backdated timestamps, real repository/JDBC reads to verify what
 * survived — no mocking of the retention window or the purge query.
 */
class RetentionSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ProcessedEventRetentionScheduler processedEventRetentionScheduler;

    @Autowired
    DeferredTransitionRetentionScheduler deferredTransitionRetentionScheduler;

    @Autowired
    DeferredTransitionRepository deferredTransitionRepository;

    @Test
    @Transactional
    void purgesProcessedEventsRowsOlderThanTheRetentionWindowButKeepsRecentOnes() {
        UUID oldEventId = UUID.randomUUID();
        UUID recentEventId = UUID.randomUUID();
        String consumerName = "order.retention-test";

        insertProcessedEvent(oldEventId, consumerName, OffsetDateTime.now(ZoneOffset.UTC).minusDays(8));
        insertProcessedEvent(recentEventId, consumerName, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        processedEventRetentionScheduler.purgeExpired();

        assertThat(processedEventExists(oldEventId, consumerName))
                .as("an 8-day-old row is past the default 7-day retention window and should be purged")
                .isFalse();
        assertThat(processedEventExists(recentEventId, consumerName))
                .as("a 1-hour-old row is well inside the retention window and must survive")
                .isTrue();
    }

    @Test
    void neverPurgesAPendingDeferredTransitionRegardlessOfAge() {
        String orderId = createOrder();
        DeferredTransitionEntity veryOldPending = deferredTransitionRepository.save(
                new DeferredTransitionEntity(orderId, OrderStatus.FULFILLED, null,
                        Instant.now().minus(30, ChronoUnit.DAYS)));
        Long id = veryOldPending.getId();

        deferredTransitionRetentionScheduler.purgeResolved();

        assertThat(deferredTransitionRepository.findById(id))
                .as("a PENDING row is a live parked transition — it must never be purged by age alone")
                .isPresent();
    }

    @Test
    void purgesResolvedDeferredTransitionsOlderThanTheRetentionWindowButKeepsRecentOnes() {
        DeferredTransitionEntity oldApplied = deferredTransitionRepository.save(
                new DeferredTransitionEntity(createOrder(), OrderStatus.FULFILLED, null,
                        Instant.now().minus(30, ChronoUnit.DAYS)));
        oldApplied.resolve(DeferredTransitionStatus.APPLIED, Instant.now().minus(8, ChronoUnit.DAYS));
        deferredTransitionRepository.save(oldApplied);
        Long oldAppliedId = oldApplied.getId();

        DeferredTransitionEntity oldAbandoned = deferredTransitionRepository.save(
                new DeferredTransitionEntity(createOrder(), OrderStatus.FULFILLED, null,
                        Instant.now().minus(30, ChronoUnit.DAYS)));
        oldAbandoned.resolve(DeferredTransitionStatus.ABANDONED, Instant.now().minus(9, ChronoUnit.DAYS));
        deferredTransitionRepository.save(oldAbandoned);
        Long oldAbandonedId = oldAbandoned.getId();

        DeferredTransitionEntity recentApplied = deferredTransitionRepository.save(
                new DeferredTransitionEntity(createOrder(), OrderStatus.FULFILLED, null,
                        Instant.now().minus(1, ChronoUnit.DAYS)));
        recentApplied.resolve(DeferredTransitionStatus.APPLIED, Instant.now().minus(1, ChronoUnit.HOURS));
        deferredTransitionRepository.save(recentApplied);
        Long recentAppliedId = recentApplied.getId();

        deferredTransitionRetentionScheduler.purgeResolved();

        assertThat(deferredTransitionRepository.findById(oldAppliedId))
                .as("an APPLIED row resolved 8 days ago is past the default 7-day window").isEmpty();
        assertThat(deferredTransitionRepository.findById(oldAbandonedId))
                .as("an ABANDONED row resolved 9 days ago is past the default 7-day window").isEmpty();
        assertThat(deferredTransitionRepository.findById(recentAppliedId))
                .as("an APPLIED row resolved 1 hour ago is well inside the retention window").isPresent();
    }

    private String createOrder() {
        CreateOrderRequest request = new CreateOrderRequest("demo-customer",
                List.of(new CreateOrderItem("SKU-001", 1)));
        OrderAccepted accepted = client.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OrderAccepted.class)
                .returnResult().getResponseBody();
        return accepted.id();
    }

    private void insertProcessedEvent(UUID eventId, String consumerName, OffsetDateTime processedAt) {
        jdbcClient.sql("INSERT INTO order_service.processed_events (event_id, consumer_name, processed_at) "
                        + "VALUES (?, ?, ?)")
                .param(eventId).param(consumerName).param(processedAt)
                .update();
    }

    private boolean processedEventExists(UUID eventId, String consumerName) {
        List<Map<String, Object>> rows = jdbcClient.sql(
                        "SELECT 1 FROM order_service.processed_events WHERE event_id = ? AND consumer_name = ?")
                .param(eventId).param(consumerName)
                .query()
                .listOfRows();
        return !rows.isEmpty();
    }
}

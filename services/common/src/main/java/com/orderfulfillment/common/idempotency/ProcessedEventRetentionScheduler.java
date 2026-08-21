package com.orderfulfillment.common.idempotency;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sprint 2 goal 2, item 4: retention for the {@code processed_events} ledger
 * (docs/adr/ADR-005-idempotent-consumers-for-duplicate-delivery.md), which its own "Accepted costs"
 * section flags as growing monotonically — "a retention policy (delete rows older than N days) is
 * needed eventually... pruning is safe once records are past Kafka's own retention." This is that
 * policy: a scheduled purge of rows older than a configurable window, deliberately no more
 * sophisticated than "delete rows past their safety margin" per this project's
 * smallest-coherent-system philosophy.
 *
 * <p><b>Why the default window is 7 days.</b> {@code KafkaTopicConfig} declares every topic without
 * an explicit {@code retention.ms}, so each one runs on the broker default (Kafka's own default is
 * {@code log.retention.hours=168}, i.e. 7 days). A ledger row can only ever need to answer "was this
 * event already processed?" for as long as Kafka could still redeliver that event, so purging rows
 * older than the topic retention is safe by the same reasoning ADR-005 already states — never
 * purging a row while its event could still arrive.
 *
 * <p><b>One shared class, like {@link ProcessedEventLedger}.</b> Each of the four fan-out services
 * gets this bean automatically via {@code com.orderfulfillment.common} component scanning, and
 * {@link ConditionalOnProperty} keeps it from ever running against Scenario Service, which has no
 * {@code processed_events} table and never sets the property this class (like
 * {@link ProcessedEventLedger}) is keyed on.
 */
@Component
@ConditionalOnProperty(prefix = "orderfulfillment.reliability", name = "processed-events-table")
public class ProcessedEventRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventRetentionScheduler.class);

    private final JdbcClient jdbcClient;
    private final String deleteSql;
    private final String tableName;
    private final Duration retention;

    public ProcessedEventRetentionScheduler(
            JdbcClient jdbcClient, ProcessedEventLedger ledger,
            @Value("${orderfulfillment.retention.processed-events-days:7}") long retentionDays) {
        this.jdbcClient = jdbcClient;
        // Reuses the table name ProcessedEventLedger already validated in its own constructor,
        // rather than re-accepting and re-validating the raw property here.
        this.tableName = ledger.tableName();
        this.deleteSql = "DELETE FROM " + tableName + " WHERE processed_at < ?";
        this.retention = Duration.ofDays(retentionDays);
    }

    /**
     * Once a day by default — this is housekeeping, not a latency-sensitive path, and a table that
     * grows by (at most) a few events per demo interaction does not need finer granularity.
     */
    @Scheduled(fixedDelayString = "${orderfulfillment.retention.check-interval-ms:86400000}")
    public void purgeExpired() {
        try {
            OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(retention);
            int deleted = jdbcClient.sql(deleteSql).param(cutoff).update();
            if (deleted > 0) {
                log.info("Purged {} row(s) from {} older than {}", deleted, tableName, retention);
            }
        } catch (Exception ex) {
            // A scheduled method that throws is simply logged by Spring and retried next tick
            // (matching OutboxPublisher/IdleResetScheduler's own convention); this catch exists only
            // to log with the specific context of which table failed to purge.
            log.warn("Retention purge of {} failed; retrying on the next tick", tableName, ex);
        }
    }
}

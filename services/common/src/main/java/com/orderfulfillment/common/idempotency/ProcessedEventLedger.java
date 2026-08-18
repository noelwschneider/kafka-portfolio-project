package com.orderfulfillment.common.idempotency;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code processed_events} idempotency ledger of
 * docs/adr/ADR-005-idempotent-consumers-for-duplicate-delivery.md, shared by all four services.
 * See docs/reliability-pattern.md for how a {@code @KafkaListener} is expected to use it.
 *
 * <h2>Why this is one shared class and not a JPA entity per service</h2>
 *
 * <p>The table's DDL is frozen and identical in every schema (docs/db-ownership.md §2), so the only
 * thing that actually differs between services is the schema name. Expressing it as JPA would need
 * a {@code @MappedSuperclass}, an {@code @Embeddable} id, a subclass entity and a repository
 * interface in each of the four services — four copies of the one thing Phase 4's fan-out is most
 * likely to let drift. Two SQL statements against {@link JdbcClient} put the whole implementation
 * here, and leave a fan-out service with exactly two things to add: a Flyway migration and one line
 * of configuration.
 *
 * <p>{@code JdbcClient} joins whatever transaction is already in progress (Spring's
 * {@code JpaTransactionManager} exposes its JDBC connection to {@code DataSourceUtils}), which is
 * what makes {@link #recordProcessed} commit atomically with the surrounding business change rather
 * than in a transaction of its own.
 *
 * <h2>Why the insert, not the read, is the authority</h2>
 *
 * <p>ADR-005 describes "check, then apply". A bare check-then-act is not atomic: two threads (this
 * service runs three listener threads per topic) can both read "absent" and both apply. So
 * {@link #isProcessed} is only a cheap early-out that avoids redoing work, and
 * {@link #recordProcessed} — an {@code INSERT ... ON CONFLICT DO NOTHING} inside the business
 * transaction — is the guard that actually decides. A concurrent duplicate blocks on the
 * uncommitted row and then sees zero rows affected, which is exactly the answer it should get.
 */
@Component
public class ProcessedEventLedger {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventLedger.class);

    /** {@code schema.table} or {@code table}; deliberately restrictive, since it is interpolated. */
    private static final Pattern QUALIFIED_TABLE_NAME = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");

    private final JdbcClient jdbcClient;
    private final String insertSql;
    private final String selectSql;
    private final String tableName;

    /**
     * @param tableName the ledger table, schema-qualified. Each service sets
     *                  {@code orderfulfillment.reliability.processed-events-table} to its own
     *                  schema's copy (e.g. {@code inventory_service.processed_events}) — the tables
     *                  are per-service by contract (docs/db-ownership.md §2). The unqualified
     *                  default exists only so that a service which has not yet had the pattern
     *                  applied still starts; such a service never calls this bean, and the day it
     *                  does, its fan-out step sets the property alongside its Flyway migration.
     */
    public ProcessedEventLedger(JdbcClient jdbcClient,
                                @Value("${orderfulfillment.reliability.processed-events-table:processed_events}") String tableName) {
        if (!QUALIFIED_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException(
                    "orderfulfillment.reliability.processed-events-table must be a plain lower-case "
                            + "[schema.]table identifier, was: " + tableName);
        }
        this.jdbcClient = jdbcClient;
        this.tableName = tableName;
        this.insertSql = "INSERT INTO " + tableName
                + " (event_id, consumer_name, processed_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        this.selectSql = "SELECT count(*) FROM " + tableName + " WHERE event_id = ? AND consumer_name = ?";
    }

    /**
     * Cheap early-out: has this consumer already handled this event? Safe to call outside a
     * transaction, and deliberately <em>not</em> the thing correctness rests on — see the class
     * Javadoc. A {@code true} answer is final (ledger rows are never deleted while the event could
     * still be redelivered); a {@code false} answer only means "not yet, as of now".
     */
    public boolean isProcessed(ProcessedEventKey key) {
        Long count = jdbcClient.sql(selectSql)
                .param(key.eventId())
                .param(key.consumerName())
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }

    /**
     * Claims this event for this consumer, returning {@code true} if the caller may go on to apply
     * the business change and {@code false} if some earlier (or concurrent) delivery already did.
     *
     * <p>{@link Propagation#MANDATORY} is the mechanical enforcement of ADR-005's core rule — the
     * ledger row and the business change must commit together. Calling this outside a transaction
     * is a bug that would let a crash leave the ledger row without its side effect (event silently
     * lost) or the side effect without its ledger row (duplicate applied on redelivery), so it
     * fails loudly at the call site instead of being discovered in production.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordProcessed(ProcessedEventKey key) {
        int inserted = jdbcClient.sql(insertSql)
                .param(key.eventId())
                .param(key.consumerName())
                .param(OffsetDateTime.now(ZoneOffset.UTC)) // the PostgreSQL driver has no direct binding for java.time.Instant
                .update();
        if (inserted == 0) {
            log.info("Duplicate delivery: event {} was already processed by {}; skipping side effects",
                    key.eventId(), key.consumerName());
            return false;
        }
        return true;
    }

    /** The schema-qualified ledger table this instance writes to. Diagnostic/test use. */
    public String tableName() {
        return tableName;
    }
}

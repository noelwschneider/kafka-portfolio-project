package com.orderfulfillment.common.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * The composite primary key of {@code processed_events} (docs/db-ownership.md §2): the envelope's
 * {@code eventId} plus the <em>logical</em> consumer that handled it.
 *
 * <p>The composite key — rather than {@code eventId} alone — is what lets one event be processed
 * once by each of several different consumers, which the system genuinely needs: Order Service and
 * Fulfillment Service both consume {@code PaymentAuthorized} independently
 * (docs/adr/ADR-005-idempotent-consumers-for-duplicate-delivery.md).
 *
 * <p>{@code consumerName} is a stable, human-readable identifier of the handler, conventionally
 * {@code "<service>.<event>"} — e.g. {@code "inventory.order-created"}. It must not be derived from
 * anything that changes between deployments (a hostname, a partition, a generated client id), or a
 * redelivery after a restart would fail to match the ledger row it is supposed to match.
 */
public record ProcessedEventKey(UUID eventId, String consumerName) {

    public ProcessedEventKey {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(consumerName, "consumerName");
    }
}

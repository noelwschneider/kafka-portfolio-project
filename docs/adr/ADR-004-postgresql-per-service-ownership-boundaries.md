# ADR-004: Use PostgreSQL per-service ownership boundaries

- **Status:** Accepted. Ownership frozen in Phase 0; physically separated in Phase 3.
- **Date:** 2026-08-17 (Phase 0)

## Context

Four services need persistence, and their data is obviously related: a reservation, a payment attempt,
and a shipment all reference the same order. A single shared schema with foreign keys between them
would be the natural relational design, and it would make several queries trivial that are otherwise
awkward — "show me the order with its reservation, payment, and shipment" becomes one join.

It would also make the service boundaries fictional. `docs/planning/backend-design.md`'s PostgreSQL
Data Model section requires that "each service should ideally own its database/schema boundaries", and
`docs/planning/implementation-phases.md`'s Phase 3 exit criteria require services to be independently
stoppable and restartable with understandable boundaries. A shared schema defeats both: two services
writing the same table cannot be reasoned about, deployed, or restarted independently, and a migration
belongs to whoever runs it last.

There is also a staging constraint. Phase 1 is deliberately a modular monolith — one process, one
application — so ownership has to be meaningful before the services are physically separate.

## Decision

Every table has exactly one owning service. Only the owner reads or writes it; cross-service data
travels as events (`docs/events/event-catalog.md`), never as shared SQL. The full mapping is
`docs/db-ownership.md`.

- **One schema per service**, each with its own Flyway migration history:
  `order_service`, `inventory_service`, `payment_service`, `fulfillment_service`, `scenario_service`.
- Locally these schemas may live in one PostgreSQL server. The boundary is enforced by convention and
  by construction (no cross-schema queries, separate migration timelines), not by network separation.
- **No foreign keys across schemas.** `order_id` appears in four schemas and is a foreign key in only
  one — `order_service` — where `orders` actually lives. Elsewhere it is a correlation identifier the
  database cannot enforce.
- **Reliability tables are per-service, not shared.** Each service gets its own `processed_events`;
  `outbox_events` exists only in Order Service (ADR-006). `docs/planning/backend-design.md` groups
  these under a "shared reliability tables" heading, which describes the shared *pattern* — its own
  next sentence requires per-service tables, and `docs/db-ownership.md` §2 resolves it explicitly.

## Alternatives considered

**One shared schema with foreign keys across all four domains.** Simplest to query, strongest
integrity guarantees, and the right answer for a monolith that will stay one. Rejected because it makes
the service extraction in Phase 3 impossible without a data migration, and because it lets any service
silently depend on another's internals — the failure mode that makes distributed monoliths worse than
either alternative. The integrity it buys is also less valuable than it looks here: an order and its
shipment are eventually consistent by design, so a foreign key would be asserting an invariant the
architecture does not actually hold.

**A separate PostgreSQL server (or container) per service, from the start.** The strongest boundary,
and closest to how this would be deployed for real. Rejected for local development as
disproportionate: four database containers to start, four connection configurations, four sets of
credentials, and four times the memory, all to enforce a boundary that one schema per service plus a
code review already enforces. `docs/planning/backend-design.md` explicitly permits sharing one server
locally. Nothing in this decision prevents splitting later — the schema-per-service layout is exactly
what makes that a configuration change.

**Shared read access, private write access** — each service writes only its own tables but may read
others' for convenience (for example Order Service reading `shipments` to render order details).
Tempting, and it would remove the need for `ShipmentCreated` to carry anything. Rejected because read
coupling is still coupling: it makes another service's schema part of your contract, so any migration
becomes a cross-service coordination problem, and it removes the reason the event exists.

**A single shared `processed_events` table for all consumers.** Superficially DRY. Rejected on
correctness, not taste: the deduplication insert must commit in the same local transaction as the
business change it guards, which is impossible if the ledger lives in another service's schema. It
would also put four services' consumers into one write hotspot and couple four migration histories.

## Consequences and tradeoffs

**Accepted costs.**

- No cross-domain joins. Assembling a full picture of an order means several API calls or a read
  model, and the frontend's order detail page shows what Order Service knows — status and history —
  with payment and shipment fetched separately if needed.
- No referential integrity across boundaries. Every service must tolerate an `order_id` it has never
  seen, which is not hypothetical under at-least-once delivery and partial failure.
- Duplicated shape: four `processed_events` tables with identical DDL, and the same `order_id` column
  in four schemas. Deliberate duplication, and cheaper than the coupling it avoids.
- Product data ends up split — `display_name` in Inventory Service, `unit_price` in Order Service
  (`docs/db-ownership.md`, "Where prices come from"). The honest cost of having no product service,
  which the project's scope rules out.
- Five migration histories to keep in order.

**What it buys.**

- Phase 3's extraction is a build-file and deployment change, not a data migration.
- Each service can be stopped, restarted, and migrated independently, which is exactly what Scenario 5
  demonstrates.
- Every cross-service dependency is visible in `docs/events/event-catalog.md`, because there is
  nowhere else for one to hide.
